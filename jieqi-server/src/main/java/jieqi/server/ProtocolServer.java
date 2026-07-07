package jieqi.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 协议入口：解析 messageType，维护连接 Session，并实现 P1 的最小登录/匹配骨架。
 */
final class ProtocolServer {
    static final int MAX_INBOUND_BYTES = 1024;
    static final int ERROR_BAD_JSON = 4001;
    static final int ERROR_AUTH = 1001;
    static final int ERROR_DUPLICATE_LOGIN = 1002;

    private final Core.ServerConfig config;
    private final Core.AccountStore accounts;
    private final Map<Core.ClientChannel, Core.Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Core.Session> onlineUsers = new ConcurrentHashMap<>();
    private final Deque<Core.Session> waiting = new ArrayDeque<>();
    private final AtomicInteger roomSequence = new AtomicInteger(1);

    ProtocolServer(Core.ServerConfig config) {
        this(config, new Core.AccountStore(config.usersFile));
    }

    ProtocolServer(Core.ServerConfig config, Core.AccountStore accounts) {
        this.config = Objects.requireNonNull(config, "config");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
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
                case "ping" -> handlePing(session, json);
                case "resign" -> handleResign(session);
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
            GameRoom room = new GameRoom("room_" + roomSequence.getAndIncrement(), opponent, session);
            opponent.room = room;
            session.room = room;
            opponent.state = Core.SessionState.IN_ROOM;
            session.state = Core.SessionState.IN_ROOM;
            opponent.send(Messages.matchSuccess(room.id, session.userId, session.nickname));
            session.send(Messages.matchSuccess(room.id, opponent.userId, opponent.nickname));
            System.out.println("match success: " + opponent.userId + " vs " + session.userId + " in " + room.id);
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

    private void handleResign(Core.Session session) {
        if (session.room instanceof GameRoom room) {
            room.resign(session);
        } else {
            System.out.println("ignore Resign outside room: " + session.userId);
        }
    }

    private boolean requireLogin(Core.Session session) {
        if (session.userId != null) {
            return true;
        }
        session.send(Messages.error(ERROR_AUTH, "login required"));
        return false;
    }

    private static final class GameRoom {
        final String id;
        final Core.Session red;
        final Core.Session black;
        private boolean redReady;
        private boolean blackReady;
        private Boolean redWannaFirst;
        private Boolean blackWannaFirst;
        private boolean finished;

        GameRoom(String id, Core.Session red, Core.Session black) {
            this.id = id;
            this.red = red;
            this.black = black;
        }

        synchronized void ready(Core.Session session) {
            if (finished) {
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
            }
        }

        synchronized void requestFirstHand(Core.Session session, boolean wannaFirst) {
            if (finished) {
                return;
            }
            if (session == red) {
                redWannaFirst = wannaFirst;
            } else if (session == black) {
                blackWannaFirst = wannaFirst;
            }
            System.out.println("first hand request in " + id + ": " + session.userId + "=" + wannaFirst);
        }

        synchronized void resign(Core.Session session) {
            if (finished) {
                return;
            }
            Core.Session winner = opponentOf(session);
            Color winnerColor = winner == red ? Color.RED : Color.BLACK;
            broadcast(Messages.gameOver(winnerColor.json(), "resign", winner.userId));
            finished = true;
        }

        synchronized void disconnect(Core.Session session) {
            if (finished) {
                return;
            }
            Core.Session winner = opponentOf(session);
            if (winner.channel.isOpen()) {
                Color winnerColor = winner == red ? Color.RED : Color.BLACK;
                winner.send(Messages.gameOver(winnerColor.json(), "disconnect", winner.userId));
            }
            finished = true;
        }

        private Core.Session opponentOf(Core.Session session) {
            return session == red ? black : red;
        }

        private void broadcast(String json) {
            red.send(json);
            black.send(json);
        }
    }
}
