package jieqi.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Json;
import jieqi.common.PieceType;
import jieqi.rules.Legality;
import jieqi.rules.RuleEngine;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 协议入口：解析 messageType，维护连接 Session，并实现 P1 的最小登录/匹配骨架。
 */
final class ProtocolServer {
    static final int MAX_INBOUND_BYTES = 1024;
    static final int ERROR_BAD_JSON = 4001;
    static final int ERROR_AUTH = 1001;
    static final int ERROR_DUPLICATE_LOGIN = 1002;
    static final int ERROR_ILLEGAL_MOVE = 2001;
    static final int ERROR_WRONG_TURN = 2002;

    private final Core.ServerConfig config;
    private final Core.AccountStore accounts;
    private final Map<Core.ClientChannel, Core.Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Core.Session> onlineUsers = new ConcurrentHashMap<>();
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    private final Deque<Core.Session> waiting = new ArrayDeque<>();
    private final AtomicInteger roomSequence = new AtomicInteger(1);
    private final ScheduledExecutorService scheduler;

    ProtocolServer(Core.ServerConfig config) {
        this(config, new Core.AccountStore(config.usersFile));
    }

    ProtocolServer(Core.ServerConfig config, Core.AccountStore accounts) {
        this.config = Objects.requireNonNull(config, "config");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
    }

    Core.Session onConnected(Core.ClientChannel channel) {
        Core.Session session = new Core.Session(channel);
        sessions.put(channel, session);
        System.out.println("client connected: " + channel.remote());
        return session;
    }

    void onClosed(Core.ClientChannel channel) {
        Core.Session session = sessions.remove(channel);
        if (session == null) {
            return;
        }
        synchronized (this) {
            waiting.remove(session);
        }
        if (session.userId != null) {
            onlineUsers.remove(session.userId, session);
        }
        if (session.room instanceof GameRoom room) {
            room.disconnect(session);
        }
        System.out.println("client closed: " + channel.remote());
    }

    void onMessage(Core.ClientChannel channel, String message) {
        Core.Session session = sessions.computeIfAbsent(channel, Core.Session::new);
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_INBOUND_BYTES) {
            System.out.println("closing oversize frame from " + channel.remote());
            channel.closeConnection();
            return;
        }
        dispatch(session, message);
    }

    private void dispatch(Core.Session session, String text) {
        JsonObject json;
        String type;
        try {
            json = Json.parseObject(text);
            type = Json.messageType(json);
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("missing messageType");
            }
        } catch (IllegalArgumentException ex) {
            session.send(Messages.error(ERROR_BAD_JSON, "JSON format error"));
            return;
        }

        try {
            switch (type) {
                case "login" -> handleLogin(session, json);
                case "register" -> handleRegister(session, json);
                case "startmatch" -> handleStartMatch(session);
                case "requestfirsthand" -> handleRequestFirstHand(session, json);
                case "ready" -> handleReady(session);
                case "move" -> handleMove(session, json);
                case "ping" -> handlePing(session, json);
                case "resign" -> handleResign(session);
                case "serverstatus" -> handleServerStatus(session);
                default -> System.out.println("ignore unknown messageType: " + type);
            }
        } catch (IllegalArgumentException ex) {
            session.send(Messages.error(ERROR_BAD_JSON, "JSON format error"));
        } catch (RuntimeException ex) {
            System.err.println("message handling failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
        }
    }

    private void handleLogin(Core.Session session, JsonObject json) {
        String userId = Json.optString(json, "userId", null);
        String password = Json.optString(json, "password", null);
        String nickname = accounts.login(userId, password, config.autoRegisterOnLogin);
        if (nickname == null) {
            session.send(Messages.loginResult(false, "invalid userId or password", userId));
            return;
        }
        Core.Session existing = onlineUsers.get(userId);
        if (existing != null && existing != session && existing.channel.isOpen()) {
            session.send(Messages.error(ERROR_DUPLICATE_LOGIN, "duplicate login"));
            return;
        }
        session.userId = userId;
        session.nickname = nickname;
        session.state = Core.SessionState.AUTHED;
        onlineUsers.put(userId, session);
        session.send(Messages.loginResult(true, "ok", userId));
        System.out.println("player login: userId=" + userId + ", remote=" + session.channel.remote());
    }

    private void handleRegister(Core.Session session, JsonObject json) {
        String userId = Json.optString(json, "userId", null);
        String password = Json.optString(json, "password", null);
        String nickname = Json.optString(json, "nickname", userId);
        boolean success = accounts.register(userId, password, nickname);
        if (success) {
            session.userId = userId;
            session.nickname = nickname;
            session.state = Core.SessionState.AUTHED;
            onlineUsers.put(userId, session);
        }
        session.send(Messages.loginResult(success, success ? "ok" : "register failed", userId));
    }

    private void handleStartMatch(Core.Session session) {
        if (!requireLogin(session)) {
            return;
        }
        synchronized (this) {
            if (session.state == Core.SessionState.MATCHING || session.room != null) {
                return;
            }
            Core.Session opponent = pollWaitingOpponent(session);
            if (opponent == null) {
                waiting.addLast(session);
                session.state = Core.SessionState.MATCHING;
                System.out.println("player waiting for match: " + session.userId);
                return;
            }
            GameRoom room = new GameRoom("room_" + roomSequence.getAndIncrement(), opponent, session,
                    config.initialBoardMode, config.turnTimeoutMs, config.recordsDir, scheduler);
            rooms.put(room.id, room);
            opponent.room = room;
            session.room = room;
            opponent.state = Core.SessionState.IN_ROOM;
            session.state = Core.SessionState.IN_ROOM;
            opponent.send(Messages.matchSuccess(room.id, session.userId, session.nickname));
            session.send(Messages.matchSuccess(room.id, opponent.userId, opponent.nickname));
            System.out.println("match success: roomId=" + room.id
                    + ", redPlayerId=" + opponent.userId + ", blackPlayerId=" + session.userId);
        }
    }

    private Core.Session pollWaitingOpponent(Core.Session session) {
        while (!waiting.isEmpty()) {
            Core.Session candidate = waiting.removeFirst();
            if (candidate != session && candidate.channel.isOpen() && candidate.userId != null) {
                return candidate;
            }
        }
        return null;
    }

    private void handleReady(Core.Session session) {
        if (session.room instanceof GameRoom room) {
            room.ready(session);
        } else {
            System.out.println("ignore Ready outside room: " + session.userId);
        }
    }

    private void handleRequestFirstHand(Core.Session session, JsonObject json) {
        if (session.room instanceof GameRoom room) {
            room.requestFirstHand(session, Json.optBool(json, "wannaFirst", false));
        } else {
            System.out.println("ignore requestFirstHand outside room: " + session.userId);
        }
    }

    private void handlePing(Core.Session session, JsonObject json) {
        JsonElement timestamp = Json.get(json, "timestamp");
        if (timestamp == null || !timestamp.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing timestamp");
        }
        session.send(Messages.pong(timestamp.getAsLong()));
    }

    private void handleMove(Core.Session session, JsonObject json) {
        MoveMessage move = parseMove(json);
        if (session.room instanceof GameRoom room) {
            room.move(session, move);
        } else {
            session.send(Messages.moveResult(false, move.from, move.to, move.clientFlip, null, null));
            session.send(Messages.error(ERROR_ILLEGAL_MOVE, "not in game"));
        }
    }

    private void handleResign(Core.Session session) {
        if (session.room instanceof GameRoom room) {
            room.resign(session);
        } else {
            System.out.println("ignore Resign outside room: " + session.userId);
        }
    }

    private void handleServerStatus(Core.Session session) {
        int waitingUsers;
        synchronized (this) {
            waitingUsers = waiting.size();
        }
        List<Messages.RoomStatus> roomStatuses = new ArrayList<>();
        for (GameRoom room : rooms.values()) {
            roomStatuses.add(room.status());
        }
        session.send(Messages.serverStatus(onlineUsers.size(), waitingUsers, roomStatuses));
    }

    private boolean requireLogin(Core.Session session) {
        if (session.userId != null) {
            return true;
        }
        session.send(Messages.error(ERROR_AUTH, "login required"));
        return false;
    }

    private static MoveMessage parseMove(JsonObject json) {
        String fromX = requiredString(json, "fromX");
        int fromY = requiredInt(json, "fromY");
        String toX = requiredString(json, "toX");
        int toY = requiredInt(json, "toY");
        boolean isFlip = requiredBool(json, "isFlip");
        return new MoveMessage(coord(fromX, fromY), coord(toX, toY), isFlip);
    }

    private static Coord coord(String x, int y) {
        if (x == null || x.length() != 1) {
            throw new IllegalArgumentException("bad coordinate file");
        }
        return new Coord(Character.toLowerCase(x.charAt(0)) - 'a', y);
    }

    private static String requiredString(JsonObject json, String key) {
        JsonElement element = Json.get(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return element.getAsString();
    }

    private static int requiredInt(JsonObject json, String key) {
        JsonElement element = Json.get(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return element.getAsInt();
    }

    private static boolean requiredBool(JsonObject json, String key) {
        JsonElement element = Json.get(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return element.getAsBoolean();
    }

    private record MoveMessage(Coord from, Coord to, boolean clientFlip) {}

    private static final class DaemonThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jieqi-turn-timer");
            thread.setDaemon(true);
            return thread;
        }
    }

    private static final class GameRoom {
        final String id;
        private Core.Session red;
        private Core.Session black;
        final String initialBoardMode;
        final long turnTimeoutMs;
        final Path recordsDir;
        final ScheduledExecutorService scheduler;
        final Core.ServerBoard board = new Core.ServerBoard();
        final Random rng = new SecureRandom();
        private boolean redReady;
        private boolean blackReady;
        private Boolean redWannaFirst;
        private Boolean blackWannaFirst;
        private Color turn = Color.RED;
        private boolean started;
        private boolean finished;
        private ScheduledFuture<?> turnTimer;
        private String winnerId;
        private String loserId;
        private String finishReason;
        private GameRecorder recorder;

        GameRoom(String id, Core.Session red, Core.Session black, String initialBoardMode,
                 long turnTimeoutMs, Path recordsDir, ScheduledExecutorService scheduler) {
            this.id = id;
            this.red = red;
            this.black = black;
            this.initialBoardMode = initialBoardMode;
            this.turnTimeoutMs = turnTimeoutMs;
            this.recordsDir = recordsDir;
            this.scheduler = scheduler;
        }

        synchronized void ready(Core.Session session) {
            if (finished) {
                return;
            }
            if (started) {
                return;
            }
            if (session == red) {
                redReady = true;
                black.send(Messages.roomInfo(true));
            } else if (session == black) {
                blackReady = true;
                red.send(Messages.roomInfo(true));
            }
            if (redReady && blackReady) {
                System.out.println("both players ready in " + id);
                start();
            }
        }

        synchronized void requestFirstHand(Core.Session session, boolean wannaFirst) {
            if (finished) {
                return;
            }
            if (started) {
                System.out.println("ignore requestFirstHand after gameStart: roomId=" + id
                        + ", userId=" + session.userId);
                return;
            }
            if (session == red) {
                redWannaFirst = wannaFirst;
            } else if (session == black) {
                blackWannaFirst = wannaFirst;
            }
            System.out.println("first hand request in " + id + ": " + session.userId + "=" + wannaFirst);
        }

        synchronized void move(Core.Session session, MoveMessage move) {
            if (finished) {
                System.out.println("ignore move after game over in " + id + ": " + session.userId);
                return;
            }
            if (!started) {
                rejectMove(session, move, ERROR_ILLEGAL_MOVE, "game not started");
                return;
            }
            Color mover = colorOf(session);
            if (mover == null || mover != turn) {
                rejectMove(session, move, ERROR_WRONG_TURN, "not your turn");
                return;
            }
            Legality legality = RuleEngine.validate(board.snapshot(), mover, move.from, move.to);
            if (!legality.legal()) {
                rejectMove(session, move, ERROR_ILLEGAL_MOVE, "illegal move");
                return;
            }

            Core.ApplyResult result = board.apply(mover, move.from, move.to, rng);
            String moverCapture = capturedPieceFor(result, true);
            String opponentCapture = capturedPieceFor(result, false);
            session.send(Messages.moveResult(true, move.from, move.to, result.isFlip(),
                    result.flipType(), moverCapture));
            opponentOf(session).send(Messages.moveResult(true, move.from, move.to, result.isFlip(),
                    result.flipType(), opponentCapture));
            recordMove(mover, move, result.isFlip(), true, result.flipType(), moverCapture);
            logMoveResult(session, move, true, result.isFlip(), result.flipType(), moverCapture);
            if (result.kingCaptured()) {
                finishGame("checkmate", session, opponentOf(session), false);
                return;
            }
            switchTurnAfterLegalMove();
        }

        synchronized void resign(Core.Session session) {
            Core.Session winner = opponentOf(session);
            if (colorOf(session) == null || winner == session) {
                return;
            }
            finishGame("resign", winner, session, false);
        }

        synchronized void disconnect(Core.Session session) {
            if (finished) {
                return;
            }
            if (!started) {
                System.out.println("disconnect before gameStart: roomId=" + id
                        + ", disconnectedPlayerId=" + session.userId);
                finished = true;
                cancelTurnTimer();
                return;
            }
            Core.Session winner = opponentOf(session);
            if (colorOf(session) == null || winner == session) {
                return;
            }
            finishGame("disconnect", winner, session, false);
        }

        synchronized Messages.RoomStatus status() {
            return new Messages.RoomStatus(id, red.userId, black.userId,
                    started, finished, turn.json());
        }

        private Core.Session opponentOf(Core.Session session) {
            return session == red ? black : red;
        }

        private void start() {
            resolveFirstHand();
            started = true;
            turn = Color.RED;
            recorder = GameRecorder.start(id, red.userId, black.userId);
            System.out.println("gameStart: roomId=" + id + ", redPlayerId=" + red.userId
                    + ", blackPlayerId=" + black.userId + ", currentTurn=" + turn.json());
            red.send(Messages.gameStart(red.userId, black.userId, Color.RED,
                    board.snapshot(), initialBoardMode));
            black.send(Messages.gameStart(red.userId, black.userId, Color.BLACK,
                    board.snapshot(), initialBoardMode));
            startTurnTimer();
        }

        private void resolveFirstHand() {
            boolean redWantsFirst = Boolean.TRUE.equals(redWannaFirst);
            boolean blackWantsFirst = Boolean.TRUE.equals(blackWannaFirst);
            if (!redWantsFirst && blackWantsFirst) {
                Core.Session oldRed = red;
                red = black;
                black = oldRed;
                Boolean oldRedWannaFirst = redWannaFirst;
                redWannaFirst = blackWannaFirst;
                blackWannaFirst = oldRedWannaFirst;
                System.out.println("first hand resolved with color swap: roomId=" + id
                        + ", redPlayerId=" + red.userId + ", blackPlayerId=" + black.userId);
            }
        }

        private void switchTurnAfterLegalMove() {
            cancelTurnTimer();
            turn = turn.opposite();
            startTurnTimer();
        }

        private void startTurnTimer() {
            if (finished || !started || turnTimeoutMs <= 0) {
                return;
            }
            Core.Session player = sessionOf(turn);
            turnTimer = scheduler.schedule(() -> onTurnTimeout(player), turnTimeoutMs, TimeUnit.MILLISECONDS);
        }

        private void cancelTurnTimer() {
            if (turnTimer != null) {
                turnTimer.cancel(false);
                turnTimer = null;
            }
        }

        private void onTurnTimeout(Core.Session loser) {
            synchronized (this) {
                if (finished || !started || loser != sessionOf(turn)) {
                    return;
                }
                System.out.println("timeout: roomId=" + id + ", loserId=" + loser.userId
                        + ", winnerId=" + opponentOf(loser).userId);
                finishGame("timeout", opponentOf(loser), loser, true);
            }
        }

        private void finishGame(String reason, Core.Session winner, Core.Session loser, boolean sendTimeout) {
            if (finished) {
                return;
            }
            finished = true;
            cancelTurnTimer();
            winnerId = winner.userId;
            loserId = loser.userId;
            finishReason = reason;
            Color winnerColor = winner == red ? Color.RED : Color.BLACK;

            if ("resign".equals(reason)) {
                System.out.println("resign: roomId=" + id + ", loserId=" + loserId + ", winnerId=" + winnerId);
            } else if ("disconnect".equals(reason)) {
                System.out.println("disconnect: roomId=" + id
                        + ", disconnectedPlayerId=" + loserId + ", winnerId=" + winnerId);
            }
            if (recorder != null) {
                recorder.finish(winnerColor.json(), winnerId, reason);
                try {
                    recorder.writeTo(recordsDir);
                } catch (Exception ex) {
                    System.err.println("write game record failed: roomId=" + id + ", error=" + ex.getMessage());
                }
            }

            if (sendTimeout) {
                broadcastOpen(Messages.timeout(loserId, winnerId));
            }
            String gameOver = Messages.gameOver(winnerColor.json(), reason, winnerId);
            if ("disconnect".equals(reason)) {
                sendIfOpen(winner, gameOver);
            } else {
                broadcastOpen(gameOver);
            }
            System.out.println("game over in " + id + ": reason=" + finishReason
                    + ", winner=" + winnerId + ", loser=" + loserId);
        }

        private void rejectMove(Core.Session session, MoveMessage move, int code, String message) {
            session.send(Messages.moveResult(false, move.from, move.to, move.clientFlip, null, null));
            session.send(Messages.error(code, message));
            recordMove(colorOf(session), move, move.clientFlip, false, null, null);
            logMoveResult(session, move, false, move.clientFlip, null, null);
        }

        private Color colorOf(Core.Session session) {
            if (session == red) {
                return Color.RED;
            }
            if (session == black) {
                return Color.BLACK;
            }
            return null;
        }

        private Core.Session sessionOf(Color color) {
            return color == Color.RED ? red : black;
        }

        private static String capturedPieceFor(Core.ApplyResult result, boolean forMover) {
            PieceType captured = result.capturedType();
            if (captured == null) {
                return null;
            }
            if (result.capturedWasHidden() && !forMover) {
                return "NULL";
            }
            return captured.json();
        }

        private void recordMove(Color mover, MoveMessage move, boolean isFlip, boolean valid,
                                PieceType flipResult, String capturedPiece) {
            if (recorder != null) {
                recorder.recordMove(mover, move.from, move.to, isFlip, valid, flipResult, capturedPiece);
            }
        }

        private void logMoveResult(Core.Session session, MoveMessage move, boolean valid, boolean isFlip,
                                   PieceType flipResult, String capturedPiece) {
            System.out.println("moveResult: roomId=" + id + ", mover=" + session.userId
                    + ", from=" + coordText(move.from) + ", to=" + coordText(move.to)
                    + ", valid=" + valid + ", isFlip=" + isFlip
                    + ", flipResult=" + (flipResult == null ? "null" : flipResult.json())
                    + ", capturedPiece=" + capturedPiece);
        }

        private static String coordText(Coord coord) {
            return String.valueOf((char) ('a' + coord.file())) + coord.rank();
        }

        private void broadcast(String json) {
            red.send(json);
            black.send(json);
        }

        private void broadcastOpen(String json) {
            sendIfOpen(red, json);
            sendIfOpen(black, json);
        }

        private void sendIfOpen(Core.Session session, String json) {
            if (session.channel.isOpen()) {
                session.send(json);
            }
        }
    }
}
