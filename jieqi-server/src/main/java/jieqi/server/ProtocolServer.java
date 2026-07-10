/*
 * 文件功能：揭棋 WebSocket JSON 协议入口与房间状态机实现。
 * 所属模块：jieqi-server。
 * 使用场景：ServerMain 的 WebSocket 回调和单元测试都会进入本类，用于处理登录、匹配、开局、走子、终局、观测状态和棋谱记录。
 */
package jieqi.server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Json;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.Legality;
import jieqi.rules.RepetitionTracker;
import jieqi.rules.RepetitionVerdict;
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
 * WebSocket 协议入口。
 *
 * <p>本类按设计文档的 WebSocket+JSON 公共接口解析 {@code messageType}，维护在线用户、
 * 匹配队列和房间集合。房间内的走子、计时、认输、断线和终局逻辑由内部 {@link GameRoom}
 * 串行保护，避免网络线程并发修改棋局。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * ProtocolServer server = new ProtocolServer(new Core.ServerConfig());
 * Core.Session session = server.onConnected(fakeChannel);
 * server.onMessage(fakeChannel, "{\"messageType\":\"ping\",\"timestamp\":1}");
 * }</pre>
 */
final class ProtocolServer {
    /** 入站 JSON 帧上限来自设计文档 Q27，超限直接断开以保护服务端。 */
    static final int MAX_INBOUND_BYTES = 1024;
    /** JSON 格式错误或缺少 messageType。 */
    static final int ERROR_BAD_JSON = 4001;
    /** 未登录却访问需要身份的接口。 */
    static final int ERROR_AUTH = 1001;
    /** 同一 userId 已有在线连接。 */
    static final int ERROR_DUPLICATE_LOGIN = 1002;
    /** 走子不符合规则或对局状态。 */
    static final int ERROR_ILLEGAL_MOVE = 2001;
    /** 非当前行棋方发起 move。 */
    static final int ERROR_WRONG_TURN = 2002;
    /** 请求的已落盘棋谱不存在。 */
    static final int ERROR_RECORD_NOT_FOUND = 3001;
    private static final int DEFAULT_RECORD_LIMIT = 20;
    private static final int MAX_RECORD_LIMIT = 100;

    /** 服务端配置集中保存端口、计时、棋谱目录和重复判定阈值，避免散落常量。 */
    private final Core.ServerConfig config;
    /** 账户存储可在测试中注入内存实现，生产默认按配置落盘。 */
    private final Core.AccountStore accounts;
    /** 通道到 Session 的映射，用于从 WebSocket 回调找到业务会话。 */
    private final Map<Core.ClientChannel, Core.Session> sessions = new ConcurrentHashMap<>();
    /** userId 到在线 Session 的映射，用于重复登录检测和 serverStatus。 */
    private final Map<String, Core.Session> onlineUsers = new ConcurrentHashMap<>();
    /** 当前所有房间，包含未结束和已结束房间，便于调试查询。 */
    private final Map<String, GameRoom> rooms = new ConcurrentHashMap<>();
    /** 匹配等待队列需要保序，因此用 Deque 并在访问时加 ProtocolServer 锁。 */
    private final Deque<Core.Session> waiting = new ArrayDeque<>();
    /** 房间编号单调递增，测试可稳定断言 room_1。 */
    private final AtomicInteger roomSequence = new AtomicInteger(1);
    /** 统一调度回合计时任务，房间内再用 synchronized 做状态保护。 */
    private final ScheduledExecutorService scheduler;
    /** 棋谱查询只读磁盘，不持有或访问 GameRoom，保证查询与在线对局状态隔离。 */
    private final GameRecordRepository gameRecords;

    /**
     * 使用配置中的账户文件创建协议服务器。
     *
     * @param config 服务端配置。
     * @throws NullPointerException 当 config 为空时抛出。
     * @apiNote 使用示例：{@code new ProtocolServer(Core.ServerConfig.fromEnv());}
     */
    ProtocolServer(Core.ServerConfig config) {
        this(config, new Core.AccountStore(config.usersFile));
    }

    /**
     * 创建协议服务器，并允许测试注入账户存储。
     *
     * @param config 服务端配置。
     * @param accounts 账户存储。
     * @throws NullPointerException 当 config 或 accounts 为空时抛出。
     * @apiNote 使用示例：{@code new ProtocolServer(config, new Core.AccountStore(null));}
     */
    ProtocolServer(Core.ServerConfig config, Core.AccountStore accounts) {
        this.config = Objects.requireNonNull(config, "config");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.gameRecords = new GameRecordRepository(config.recordsDir);
        // 单线程调度器让超时回调顺序可预测；真正修改房间状态仍由 GameRoom 锁保护。
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());
    }

    /**
     * 建立一个新的客户端 Session。
     *
     * @param channel 客户端传输通道。
     * @return 新创建并保存的会话。
     * @throws RuntimeException 当前实现只保存内存状态，不主动抛出异常。
     * @apiNote 使用示例：WebSocket onOpen 时调用该方法。
     */
    Core.Session onConnected(Core.ClientChannel channel) {
        Core.Session session = new Core.Session(channel);
        sessions.put(channel, session);
        System.out.println("client connected: " + channel.remote());
        return session;
    }

    /**
     * 处理连接关闭并清理等待队列、在线用户和房间状态。
     *
     * @param channel 关闭的客户端通道。
     * @throws RuntimeException 当前实现不主动抛出异常；房间断线逻辑内部保证幂等。
     * @apiNote 使用示例：WebSocket onClose 时调用该方法。
     */
    void onClosed(Core.ClientChannel channel) {
        Core.Session session = sessions.remove(channel);
        if (session == null) {
            return;
        }
        synchronized (this) {
            // 未开局前断线只清理等待队列，避免误判为对局中 disconnect 负。
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

    /**
     * 处理一个客户端 JSON 文本帧。
     *
     * @param channel 发送消息的客户端通道。
     * @param message JSON 文本。
     * @throws RuntimeException 当前方法捕获协议异常；底层通道异常仍可能向外抛出。
     * @apiNote 使用示例：{@code protocol.onMessage(channel, "{\"messageType\":\"serverStatus\"}");}
     */
    void onMessage(Core.ClientChannel channel, String message) {
        Core.Session session = sessions.computeIfAbsent(channel, Core.Session::new);
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_INBOUND_BYTES) {
            System.out.println("closing oversize frame from " + channel.remote());
            channel.closeConnection();
            return;
        }
        dispatch(session, message);
    }

    /**
     * 解析 messageType 并分发到对应处理器。
     *
     * @param session 发送消息的会话。
     * @param text 原始 JSON 文本。
     * @throws RuntimeException 单个处理器的未预期异常会被捕获并记录，不让服务端崩溃。
     * @apiNote 使用示例：{@code dispatch(session, "{\"messageType\":\"ping\",\"timestamp\":1}");}
     */
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
            // messageType 由 Json.messageType 统一规范化，保证 Login/Ready/Resign 等大小写兼容。
            switch (type) {
                case "login" -> handleLogin(session, json);
                case "register" -> handleRegister(session, json);
                case "startmatch" -> handleStartMatch(session);
                case "cancelmatch" -> handleCancelMatch(session);
                case "requestfirsthand" -> handleRequestFirstHand(session, json);
                case "ready" -> handleReady(session);
                case "move" -> handleMove(session, json);
                case "ping" -> handlePing(session, json);
                case "resign" -> handleResign(session);
                case "requestdraw" -> handleRequestDraw(session);
                case "drawresponse" -> handleDrawResponse(session, json);
                case "serverstatus" -> handleServerStatus(session);
                case "querygamerecords" -> handleQueryGameRecords(session, json);
                case "querygamerecord" -> handleQueryGameRecord(session, json);
                // 未知消息按设计文档静默忽略并打日志，避免影响跨组兼容。
                default -> System.out.println("ignore unknown messageType: " + type);
            }
        } catch (IllegalArgumentException ex) {
            session.send(Messages.error(ERROR_BAD_JSON, "JSON format error"));
        } catch (RuntimeException ex) {
            System.err.println("message handling failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
        }
    }

    /**
     * 处理登录消息。
     *
     * @param session 当前连接会话。
     * @param json 登录 JSON，包含 userId 和 password。
     * @throws RuntimeException 当账户存储读写异常未被底层吞掉时可能抛出。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"Login","userId":"u1","password":"p"}}。
     */
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
        // 登录成功后才写 onlineUsers，避免未认证连接污染在线人数和重复登录判断。
        session.userId = userId;
        session.nickname = nickname;
        session.state = Core.SessionState.AUTHED;
        onlineUsers.put(userId, session);
        session.send(Messages.loginResult(true, "ok", userId));
        System.out.println("player login: userId=" + userId + ", remote=" + session.channel.remote());
    }

    /**
     * 处理注册消息并在成功后直接进入已登录状态。
     *
     * @param session 当前连接会话。
     * @param json 注册 JSON，包含 userId、password，可选 nickname。
     * @throws RuntimeException 当账户存储读写异常未被底层吞掉时可能抛出。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"register","userId":"u1","password":"p"}}。
     */
    private void handleRegister(Core.Session session, JsonObject json) {
        String userId = Json.optString(json, "userId", null);
        String password = Json.optString(json, "password", null);
        String nickname = Json.optString(json, "nickname", userId);
        boolean success = accounts.register(userId, password, nickname);
        if (success) {
            // 注册后立即登录，减少联调客户端必须额外发送 Login 的状态分支。
            session.userId = userId;
            session.nickname = nickname;
            session.state = Core.SessionState.AUTHED;
            onlineUsers.put(userId, session);
        }
        session.send(Messages.loginResult(success, success ? "ok" : "register failed", userId));
    }

    /**
     * 处理开始匹配消息。
     *
     * @param session 当前连接会话。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：客户端登录后发送 {@code {"messageType":"startMatch"}}。
     */
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
                // 只有认证用户会进入等待队列，serverStatus 的 waitingUsers 因此可直接读队列长度。
                waiting.addLast(session);
                session.state = Core.SessionState.MATCHING;
                System.out.println("player waiting for match: " + session.userId);
                return;
            }
            // 队首玩家默认先成为红方，requestFirstHand 会在 gameStart 前按意愿调整。
            GameRoom room = new GameRoom("room_" + roomSequence.getAndIncrement(), opponent, session,
                    config.initialBoardMode, config.turnTimeoutMs, config.firstHandWindowMs,
                    config.autoReadyAfterMs, config.recordsDir,
                    config.repetitionLimit, config.repetitionMinRepeats, config.noCaptureLimitHalfMoves,
                    scheduler);
            rooms.put(room.id, room);
            opponent.room = room;
            session.room = room;
            opponent.state = Core.SessionState.IN_ROOM;
            session.state = Core.SessionState.IN_ROOM;
            opponent.send(Messages.matchSuccess(room.id, session.userId, session.nickname));
            session.send(Messages.matchSuccess(room.id, opponent.userId, opponent.nickname));
            System.out.println("match success: roomId=" + room.id
                    + ", redPlayerId=" + opponent.userId + ", blackPlayerId=" + session.userId);
            room.scheduleAutoReady();
        }
    }

    /**
     * 从等待队列中取出一个可用对手。
     *
     * @param session 当前请求匹配的玩家，不能匹配自己。
     * @return 可匹配的对手；没有时返回 null。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：仅在持有 {@code ProtocolServer} 锁时调用。
     */
    private Core.Session pollWaitingOpponent(Core.Session session) {
        while (!waiting.isEmpty()) {
            Core.Session candidate = waiting.removeFirst();
            // 跳过已断线或异常残留的会话，避免把不可用连接放入房间。
            if (candidate != session && candidate.channel.isOpen() && candidate.userId != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 处理 cancelMatch 消息。
     *
     * @param session 发起取消匹配的会话；已登录且仍在 MATCHING 时会从等待队列移除并回到 AUTHED。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：玩家 startMatch 进入等待队列后发送 {@code {"messageType":"cancelMatch"}}。
     */
    private void handleCancelMatch(Core.Session session) {
        if (!requireLogin(session)) {
            return;
        }
        synchronized (this) {
            if (session.state != Core.SessionState.MATCHING) {
                // cancelMatch 允许联调客户端重复发送；非匹配态保持幂等忽略，避免误伤已创建房间。
                System.out.println("ignore cancelMatch outside matching: " + session.userId);
                return;
            }
            // 只有仍在等待队列的会话才能取消匹配；状态回退让该用户可立即再次 startMatch。
            boolean removed = waiting.remove(session);
            session.state = Core.SessionState.AUTHED;
            System.out.println("cancel match: userId=" + session.userId + ", removedFromQueue=" + removed);
        }
    }

    /**
     * 处理 Ready 消息。
     *
     * @param session 当前连接会话。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：双方匹配成功后各发送 {@code {"messageType":"Ready"}}。
     */
    private void handleReady(Core.Session session) {
        if (session.room instanceof GameRoom room) {
            room.ready(session);
        } else {
            System.out.println("ignore Ready outside room: " + session.userId);
        }
    }

    /**
     * 处理先手意愿消息。
     *
     * @param session 当前连接会话。
     * @param json 请求 JSON，包含 wannaFirst。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"requestFirstHand","wannaFirst":true}}。
     */
    private void handleRequestFirstHand(Core.Session session, JsonObject json) {
        if (session.room instanceof GameRoom room) {
            room.requestFirstHand(session, Json.optBool(json, "wannaFirst", false));
        } else {
            System.out.println("ignore requestFirstHand outside room: " + session.userId);
        }
    }

    /**
     * 处理心跳 ping，并原样返回 timestamp。
     *
     * @param session 当前连接会话。
     * @param json ping JSON，必须包含 timestamp。
     * @throws IllegalArgumentException 当 timestamp 缺失或不是基本类型时抛出，由分发层转为 error 4001。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"ping","timestamp":1712345678901}}。
     */
    private void handlePing(Core.Session session, JsonObject json) {
        JsonElement timestamp = Json.get(json, "timestamp");
        if (timestamp == null || !timestamp.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing timestamp");
        }
        session.send(Messages.pong(timestamp.getAsLong()));
    }

    /**
     * 处理客户端 move 消息。
     *
     * @param session 当前连接会话。
     * @param json move JSON，包含 fromX/fromY/toX/toY/isFlip。
     * @throws IllegalArgumentException 当 move 字段缺失或格式错误时抛出，由分发层转为 error 4001。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"move","fromX":"b","fromY":2,"toX":"e","toY":2,"isFlip":true}}。
     */
    private void handleMove(Core.Session session, JsonObject json) {
        MoveMessage move = parseMove(json);
        if (session.room instanceof GameRoom room) {
            room.move(session, move);
        } else {
            // 非房间内 move 仍返回 moveResult，让客户端能按公共接口回滚本地落子。
            session.send(Messages.moveResult(false, move.from, move.to, move.clientFlip, null, null));
            session.send(Messages.error(ERROR_ILLEGAL_MOVE, "not in game"));
        }
    }

    /**
     * 处理认输消息。
     *
     * @param session 当前连接会话。
     * @throws RuntimeException 当前实现不主动抛出异常；重复终局由房间保证幂等。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"Resign"}}。
     */
    private void handleResign(Core.Session session) {
        if (session.room instanceof GameRoom room) {
            room.resign(session);
        } else {
            System.out.println("ignore Resign outside room: " + session.userId);
        }
    }

    /**
     * 处理协议和棋请求。
     *
     * @param session 提和玩家会话；必须处于已开局且未终局房间中才会记录待响应状态。
     * @throws RuntimeException 当前实现不主动抛出异常；房间内部会静默忽略非法状态。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"requestDraw"}}。
     */
    private void handleRequestDraw(Core.Session session) {
        if (session.room instanceof GameRoom room) {
            room.requestDraw(session);
        } else {
            System.out.println("ignore requestDraw outside room: " + session.userId);
        }
    }

    /**
     * 处理协议和棋响应。
     *
     * @param session 响应玩家会话；必须是对手对有效提和做出的响应。
     * @param json drawResponse JSON，包含 accept 布尔字段。
     * @throws RuntimeException 当前实现不主动抛出异常；缺失 accept 按拒绝处理以兼容联调客户端。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"drawResponse","accept":true}}。
     */
    private void handleDrawResponse(Core.Session session, JsonObject json) {
        if (session.room instanceof GameRoom room) {
            room.drawResponse(session, Json.optBool(json, "accept", false));
        } else {
            System.out.println("ignore drawResponse outside room: " + session.userId);
        }
    }

    /**
     * 处理调试用服务器状态查询。
     *
     * @param session 当前连接会话；允许未登录。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：客户端发送 {@code {"messageType":"serverStatus"}}。
     */
    private void handleServerStatus(Core.Session session) {
        int waitingUsers;
        synchronized (this) {
            // waiting 是 ArrayDeque，读写都在同一把锁内，避免并发匹配时看到中间状态。
            waitingUsers = waiting.size();
        }
        List<Messages.RoomStatus> roomStatuses = new ArrayList<>();
        for (GameRoom room : rooms.values()) {
            roomStatuses.add(room.status());
        }
        session.send(Messages.serverStatus(onlineUsers.size(), waitingUsers, roomStatuses));
    }

    /**
     * 处理已登录客户端的棋谱列表查询。
     *
     * @param session 当前连接会话。
     * @param json 请求 JSON，可选 offset 和 limit。
     * @throws IllegalArgumentException 分页字段类型或范围非法时抛出，由分发层转为 error 4001。
     * @apiNote 查询仅扫描落盘文件，不读取或修改在线房间。
     */
    private void handleQueryGameRecords(Core.Session session, JsonObject json) {
        if (!requireLogin(session)) {
            return;
        }
        int offset = optionalInt(json, "offset", 0);
        int requestedLimit = optionalInt(json, "limit", DEFAULT_RECORD_LIMIT);
        if (offset < 0 || requestedLimit <= 0) {
            throw new IllegalArgumentException("invalid pagination");
        }
        int limit = Math.min(requestedLimit, MAX_RECORD_LIMIT);
        GameRecordRepository.QueryResult result = gameRecords.query(offset, limit);
        session.send(Messages.gameRecordList(result.total(), offset, limit, result.records()));
    }

    /**
     * 处理已登录客户端的单局棋谱详情查询。
     *
     * @param session 当前连接会话。
     * @param json 请求 JSON，必须包含非空 roomId。
     * @throws IllegalArgumentException roomId 缺失或类型错误时抛出，由分发层转为 error 4001。
     * @apiNote roomId 只参与已解析内容比较，绝不参与文件路径拼接。
     */
    private void handleQueryGameRecord(Core.Session session, JsonObject json) {
        if (!requireLogin(session)) {
            return;
        }
        JsonElement roomIdElement = Json.get(json, "roomId");
        if (roomIdElement == null || !roomIdElement.isJsonPrimitive()
                || !roomIdElement.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("invalid roomId");
        }
        String roomId = roomIdElement.getAsString();
        if (roomId.isBlank()) {
            throw new IllegalArgumentException("empty roomId");
        }
        gameRecords.findByRoomId(roomId).ifPresentOrElse(
                record -> session.send(Messages.gameRecord(record)),
                () -> session.send(Messages.error(ERROR_RECORD_NOT_FOUND, "game record not found")));
    }

    /**
     * 校验会话是否已登录。
     *
     * @param session 当前连接会话。
     * @return 已登录返回 {@code true}，否则发送 error 并返回 {@code false}。
     * @throws RuntimeException 当底层通道发送失败时可能抛出。
     * @apiNote 使用示例：匹配前调用 {@code if (!requireLogin(session)) return;}。
     */
    private boolean requireLogin(Core.Session session) {
        if (session.userId != null) {
            return true;
        }
        session.send(Messages.error(ERROR_AUTH, "login required"));
        return false;
    }

    /**
     * 解析客户端 move JSON 为内部坐标对象。
     *
     * @param json 客户端 move 消息 JSON。
     * @return 解析后的 move 消息。
     * @throws IllegalArgumentException 当任一必填字段缺失或格式错误时抛出。
     * @apiNote 使用示例：{@code MoveMessage move = parseMove(json);}
     */
    private static MoveMessage parseMove(JsonObject json) {
        String fromX = requiredString(json, "fromX");
        int fromY = requiredInt(json, "fromY");
        String toX = requiredString(json, "toX");
        int toY = requiredInt(json, "toY");
        boolean isFlip = requiredBool(json, "isFlip");
        return new MoveMessage(coord(fromX, fromY), coord(toX, toY), isFlip);
    }

    /**
     * 将协议坐标字段转换为内部坐标。
     *
     * @param x 列字母，范围由规则层进一步校验。
     * @param y 行号。
     * @return 内部 {@link Coord}。
     * @throws IllegalArgumentException 当列字段不是单个字符时抛出。
     * @apiNote 使用示例：{@code Coord c = coord("b", 2);}
     */
    private static Coord coord(String x, int y) {
        if (x == null || x.length() != 1) {
            throw new IllegalArgumentException("bad coordinate file");
        }
        return new Coord(Character.toLowerCase(x.charAt(0)) - 'a', y);
    }

    /**
     * 读取必填字符串字段。
     *
     * @param json JSON 对象。
     * @param key 字段名。
     * @return 字符串值。
     * @throws IllegalArgumentException 当字段缺失或不是基本类型时抛出。
     * @apiNote 使用示例：{@code String x = requiredString(json, "fromX");}
     */
    private static String requiredString(JsonObject json, String key) {
        JsonElement element = Json.get(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return element.getAsString();
    }

    /**
     * 读取必填整数字段。
     *
     * @param json JSON 对象。
     * @param key 字段名。
     * @return 整数值。
     * @throws IllegalArgumentException 当字段缺失或不能转换为整数时抛出。
     * @apiNote 使用示例：{@code int y = requiredInt(json, "fromY");}
     */
    private static int requiredInt(JsonObject json, String key) {
        JsonElement element = Json.get(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return element.getAsInt();
    }

    /**
     * 读取可选整数字段，缺失时使用默认值。
     *
     * @param json JSON 对象。
     * @param key 字段名。
     * @param defaultValue 字段缺失时的默认值。
     * @return 字段整数值或默认值。
     * @throws IllegalArgumentException 字段存在但不是整数数值时抛出。
     */
    private static int optionalInt(JsonObject json, String key, int defaultValue) {
        JsonElement element = Json.get(json, key);
        if (element == null) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("invalid " + key);
        }
        try {
            return element.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("invalid " + key, ex);
        }
    }

    /**
     * 读取必填布尔字段。
     *
     * @param json JSON 对象。
     * @param key 字段名。
     * @return 布尔值。
     * @throws IllegalArgumentException 当字段缺失或不能转换为布尔值时抛出。
     * @apiNote 使用示例：{@code boolean flip = requiredBool(json, "isFlip");}
     */
    private static boolean requiredBool(JsonObject json, String key) {
        JsonElement element = Json.get(json, key);
        if (element == null || !element.isJsonPrimitive()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return element.getAsBoolean();
    }

    /**
     * 客户端 move 消息在协议层内的不可变表示。
     *
     * @param from 起点坐标。
     * @param to 终点坐标。
     * @param clientFlip 客户端声明的 isFlip；服务端合法走子后仍以真实棋盘结果为准。
     */
    private record MoveMessage(Coord from, Coord to, boolean clientFlip) {}

    /**
     * 创建后台计时线程的工厂。
     *
     * <p>计时线程设为 daemon，是为了测试 JVM 或命令行进程退出时不被未结束的定时器阻塞。</p>
     */
    private static final class DaemonThreadFactory implements ThreadFactory {
        /**
         * 创建一个后台线程。
         *
         * @param runnable 计时任务。
         * @return daemon 线程。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：{@code Executors.newSingleThreadScheduledExecutor(new DaemonThreadFactory());}
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "jieqi-turn-timer");
            thread.setDaemon(true);
            return thread;
        }
    }

    /**
     * 将规则模块重复判定结果映射为服务端终局结果。
     *
     * @param verdict 规则模块返回的重复/无吃子判定。
     * @param mover 本步行棋方。
     * @return 需要终局时返回结果；{@link RepetitionVerdict#NONE} 返回 null。
     * @throws NullPointerException 当 verdict 为空时由 switch 抛出。
     * @apiNote 使用示例：{@code RepetitionOutcome outcome = repetitionOutcome(verdict, Color.RED);}
     */
    static RepetitionOutcome repetitionOutcome(RepetitionVerdict verdict, Color mover) {
        return switch (verdict) {
            case NONE -> null;
            case REPETITION_LOSS -> new RepetitionOutcome(false, mover.opposite(), "repetition");
            case REPETITION_DRAW -> new RepetitionOutcome(true, null, "repetition");
            case DRAW_NO_CAPTURE -> new RepetitionOutcome(true, null, "noCapture");
        };
    }

    /**
     * 重复判定映射后的服务端终局摘要。
     *
     * @param draw 是否和棋。
     * @param winnerColor 非和棋时的胜方颜色。
     * @param reason gameOver.reason 字段。
     */
    record RepetitionOutcome(boolean draw, Color winnerColor, String reason) {}

    /**
     * 单个房间的权威状态机。
     *
     * <p>房间持有真实棋盘、红黑双方、Ready 状态、当前回合、计时器、终局状态和棋谱记录器。
     * 所有会改变房间状态的方法都使用 {@code synchronized}，因为 WebSocket 回调和计时任务可能来自不同线程。</p>
     */
    private static final class GameRoom {
        /** 房间编号，用于协议、日志和棋谱文件名。 */
        final String id;
        /** 当前红方 Session；requestFirstHand 可能在 gameStart 前交换红黑。 */
        private Core.Session red;
        /** 当前黑方 Session；requestFirstHand 可能在 gameStart 前交换红黑。 */
        private Core.Session black;
        /** initialBoard 输出模式，保持与服务端配置一致。 */
        final String initialBoardMode;
        /** 单步超时时间，测试可设置短值以避免真实等待 65 秒。 */
        final long turnTimeoutMs;
        /** Ready 后先手协商窗口，正数表示延迟开局，0 或负数保持立即开局兼容。 */
        final long firstHandWindowMs;
        /** 匹配成功后的自动 Ready 兜底时间，0 或负数表示关闭，保持手动 Ready 协议不变。 */
        final long autoReadyAfterMs;
        /** 棋谱目录，允许为 null 以禁用测试落盘。 */
        final Path recordsDir;
        /** 重复/无吃子判定器，每局单独持有，避免跨房间状态污染。 */
        final RepetitionTracker repetitionTracker;
        /** 回合计时调度器，由 ProtocolServer 统一创建。 */
        final ScheduledExecutorService scheduler;
        /** 服务端权威棋盘，客户端传来的 isFlip 只能作为回显参考，不能作为裁判事实。 */
        final Core.ServerBoard board = new Core.ServerBoard();
        /** 翻子使用安全随机数，符合服务器决定翻子结果的要求。 */
        final Random rng = new SecureRandom();
        /** 红方 Ready 状态。 */
        private boolean redReady;
        /** 黑方 Ready 状态。 */
        private boolean blackReady;
        /** 红方先手意愿；null 表示未表态。 */
        private Boolean redWannaFirst;
        /** 黑方先手意愿；null 表示未表态。 */
        private Boolean blackWannaFirst;
        /** 当前待响应的提和玩家；为 null 表示没有有效提和。 */
        private Core.Session pendingDrawRequester;
        /** 当前行棋方，开局后始终从红方开始。 */
        private Color turn = Color.RED;
        /** 是否已经发送 gameStart。 */
        private boolean started;
        /** 是否已经进入终局；所有终局入口都先检查该标志保证幂等。 */
        private boolean finished;
        /** 先手协商窗口任务，开局、断线或终局时需要取消以避免延迟误启动。 */
        private ScheduledFuture<?> firstHandWindowTask;
        /** 自动 Ready 兜底任务，开局、断线、终局或双方已 Ready 后取消。 */
        private ScheduledFuture<?> autoReadyTask;
        /** 当前回合计时任务，切换回合或终局时必须取消。 */
        private ScheduledFuture<?> turnTimer;
        /** 胜方玩家 ID；和棋时为 null。 */
        private String winnerId;
        /** 负方玩家 ID；和棋时为 null。 */
        private String loserId;
        /** 终局原因，供测试和日志确认幂等状态。 */
        private String finishReason;
        /** 本局棋谱记录器，在 gameStart 时创建。 */
        private GameRecorder recorder;

        /**
         * 创建房间并注入本局配置。
         *
         * @param id 房间编号。
         * @param red 默认红方 Session，通常为先进入等待队列的玩家。
         * @param black 默认黑方 Session。
         * @param initialBoardMode 初始棋盘输出模式。
         * @param turnTimeoutMs 单步超时时间。
         * @param firstHandWindowMs Ready 后先手协商窗口，0 或负数表示立即开局。
         * @param autoReadyAfterMs 匹配成功后的自动 Ready 兜底时间，0 或负数表示关闭。
         * @param recordsDir 棋谱目录。
         * @param repetitionLimit 连续重复阈值。
         * @param repetitionMinRepeats 局面重复最小次数。
         * @param noCaptureLimitHalfMoves 无吃子半步和棋阈值。
         * @param scheduler 计时任务调度器。
         * @throws RuntimeException 当重复判定器配置非法时可能由规则模块抛出。
         * @apiNote 使用示例：匹配成功时由 {@link ProtocolServer#handleStartMatch(Core.Session)} 创建。
         */
        GameRoom(String id, Core.Session red, Core.Session black, String initialBoardMode,
                 long turnTimeoutMs, long firstHandWindowMs, long autoReadyAfterMs,
                 Path recordsDir,
                 int repetitionLimit, int repetitionMinRepeats, int noCaptureLimitHalfMoves,
                 ScheduledExecutorService scheduler) {
            this.id = id;
            this.red = red;
            this.black = black;
            this.initialBoardMode = initialBoardMode;
            this.turnTimeoutMs = turnTimeoutMs;
            this.firstHandWindowMs = firstHandWindowMs;
            this.autoReadyAfterMs = autoReadyAfterMs;
            this.recordsDir = recordsDir;
            this.repetitionTracker = new RepetitionTracker(
                    repetitionLimit, repetitionMinRepeats, noCaptureLimitHalfMoves);
            this.scheduler = scheduler;
        }

        /**
         * 在匹配成功后启动自动 Ready 兜底任务。
         *
         * <p>该任务只补齐缺失的 Ready，不直接开局；开局仍交给
         * {@link #scheduleStartAfterFirstHandWindow()}，确保先手协商窗口的时序不会被绕过。</p>
         *
         * @throws RuntimeException 当调度器拒绝任务时可能抛出。
         * @apiNote 使用示例：房间放入 rooms 且双方收到 matchSuccess 后调用。
         */
        synchronized void scheduleAutoReady() {
            if (autoReadyAfterMs <= 0 || started || finished || autoReadyTask != null) {
                return;
            }
            System.out.println("auto ready scheduled: roomId=" + id + ", delayMs=" + autoReadyAfterMs);
            autoReadyTask = scheduler.schedule(() -> {
                synchronized (this) {
                    autoReadyMissingPlayers();
                }
            }, autoReadyAfterMs, TimeUnit.MILLISECONDS);
        }

        /**
         * 标记玩家已准备，并在双方 Ready 后进入先手协商窗口或立即开局。
         *
         * @param session 发送 Ready 的玩家会话；双方都 Ready 后会进入先手协商窗口或立即开局。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：双方发送 Ready 后会触发 {@link #scheduleStartAfterFirstHandWindow()}。
         */
        synchronized void ready(Core.Session session) {
            if (finished) {
                return;
            }
            if (started) {
                return;
            }
            if (session == red) {
                redReady = true;
                // 通知对手而不是自己，符合 roomInfo 表示 opponentReady 的协议语义。
                black.send(Messages.roomInfo(true));
            } else if (session == black) {
                blackReady = true;
                red.send(Messages.roomInfo(true));
            }
            if (redReady && blackReady) {
                System.out.println("both players ready in " + id);
                scheduleStartAfterFirstHandWindow();
            }
        }

        /**
         * 补齐尚未发送 Ready 的玩家，并复用原有开局流程。
         *
         * @throws RuntimeException 当发送 roomInfo 或调度先手窗口失败时可能抛出。
         * @apiNote 使用示例：由 {@link #scheduleAutoReady()} 安排的定时任务触发。
         */
        private void autoReadyMissingPlayers() {
            if (finished || started) {
                return;
            }
            boolean changed = false;
            if (!redReady) {
                redReady = true;
                changed = true;
                // 自动 Ready 也发送 roomInfo，让客户端看到的对手准备状态与手动 Ready 一致。
                black.send(Messages.roomInfo(true));
                System.out.println("auto ready player: roomId=" + id + ", userId=" + red.userId);
            }
            if (!blackReady) {
                blackReady = true;
                changed = true;
                red.send(Messages.roomInfo(true));
                System.out.println("auto ready player: roomId=" + id + ", userId=" + black.userId);
            }
            if (changed && redReady && blackReady) {
                System.out.println("both players ready in " + id + " by auto ready fallback");
                scheduleStartAfterFirstHandWindow();
            }
        }

        /**
         * 记录玩家的先手意愿。
         *
         * @param session 发送 requestFirstHand 的玩家会话。
         * @param wannaFirst true 表示希望先手；窗口结束前会参与红黑判定，开局后保持幂等忽略。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：gameStart 前发送 {@code {"messageType":"requestFirstHand","wannaFirst":true}}。
         */
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

        /**
         * 处理走子请求。
         *
         * @param session 发送 move 的玩家。
         * @param move 已解析的 move 消息。
         * @throws RuntimeException 当规则模块或棋盘应用出现未预期异常时可能抛出。
         * @apiNote 使用示例：当前行棋方发送合法 move 后广播 moveResult 并切换回合。
         */
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
                // 非法走子不改变棋盘、不切换回合、不重置计时器，但要记录到棋谱方便联调。
                rejectMove(session, move, ERROR_ILLEGAL_MOVE, "illegal move");
                return;
            }

            // 重复判定需要 before/after 两个快照，因此先保存 before 再应用服务端权威棋盘。
            BoardSnapshot before = board.snapshot();
            Core.ApplyResult result = board.apply(mover, move.from, move.to, rng);
            BoardSnapshot after = board.snapshot();
            boolean capture = result.capturedType() != null;
            RepetitionVerdict verdict = repetitionTracker.onMoveApplied(
                    before, after, mover, move.from, move.to, capture);
            String moverCapture = capturedPieceFor(result, true);
            String opponentCapture = capturedPieceFor(result, false);
            // 吃暗子时双方 capturedPiece 可能不同，避免把被吃暗子的真实类型泄露给被吃方。
            session.send(Messages.moveResult(true, move.from, move.to, result.isFlip(),
                    result.flipType(), moverCapture));
            opponentOf(session).send(Messages.moveResult(true, move.from, move.to, result.isFlip(),
                    result.flipType(), opponentCapture));
            recordMove(mover, move, result.isFlip(), true, result.flipType(), moverCapture);
            logMoveResult(session, move, true, result.isFlip(), result.flipType(), moverCapture);
            if (result.kingCaptured()) {
                // 吃王是已有终局路径，保持优先级可避免改变 B-03/B-04 行为。
                finishGame("checkmate", session, opponentOf(session), false);
                return;
            }
            if (finishByRepetitionVerdict(verdict, session)) {
                // 重复或 80 半步和棋已经终局，不能再切换回合或重启计时器。
                return;
            }
            switchTurnAfterLegalMove();
        }

        /**
         * 处理认输。
         *
         * @param session 认输玩家会话。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：收到 Resign 后对手胜，reason 为 resign。
         */
        synchronized void resign(Core.Session session) {
            Core.Session winner = opponentOf(session);
            if (colorOf(session) == null || winner == session) {
                return;
            }
            finishGame("resign", winner, session, false);
        }

        /**
         * 记录一方提和。
         *
         * <p>提和是协议协商消息，不属于走子，因此不能改变棋盘、当前回合或回合计时器。
         * 这里只保存“谁提和”，后续只有对手的 drawResponse 才能让该提和生效。</p>
         *
         * @param session 提和玩家会话。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：对局中玩家发送 {@code {"messageType":"requestDraw"}}。
         */
        synchronized void requestDraw(Core.Session session) {
            if (finished || !started) {
                System.out.println("ignore requestDraw outside playing room: roomId=" + id
                        + ", userId=" + session.userId);
                return;
            }
            if (colorOf(session) == null) {
                return;
            }
            // 后一次提和覆盖前一次提和，避免双方连续提和时保留过期请求造成错误接受。
            pendingDrawRequester = session;
            // 提和通知只发给对手，用于客户端弹窗；这里不能广播，避免提和方重复收到“待处理”提示。
            sendIfOpen(opponentOf(session), Messages.drawOffer(session.userId));
            System.out.println("requestDraw: roomId=" + id + ", requesterId=" + session.userId
                    + ", opponentId=" + opponentOf(session).userId);
        }

        /**
         * 处理对手对提和的接受或拒绝。
         *
         * @param session 响应玩家会话。
         * @param accept true 表示接受和棋并进入统一和棋终局；false 表示拒绝并继续对局。
         * @throws RuntimeException 当发送 gameOver 或写棋谱失败时可能由终局入口处理。
         * @apiNote 使用示例：对手发送 {@code {"messageType":"drawResponse","accept":true}}。
         */
        synchronized void drawResponse(Core.Session session, boolean accept) {
            if (finished || !started || pendingDrawRequester == null) {
                System.out.println("ignore drawResponse without pending draw: roomId=" + id
                        + ", userId=" + session.userId);
                return;
            }
            if (session != opponentOf(pendingDrawRequester)) {
                // 只有被提和的一方可以响应；提和方自答会破坏协议语义，因此保持静默忽略。
                System.out.println("ignore drawResponse from non-opponent: roomId=" + id
                        + ", userId=" + session.userId);
                return;
            }
            Core.Session requester = pendingDrawRequester;
            pendingDrawRequester = null;
            if (accept) {
                System.out.println("draw accepted: roomId=" + id + ", requesterId=" + requester.userId
                        + ", accepterId=" + session.userId);
                finishDraw("draw_agreed");
                return;
            }
            // 拒绝提和只清空待响应状态，继续保留原有行棋方和计时器。
            sendIfOpen(requester, Messages.drawResponseResult(false, session.userId));
            System.out.println("draw rejected: roomId=" + id + ", requesterId=" + requester.userId
                    + ", responderId=" + session.userId);
        }

        /**
         * 处理连接断开。
         *
         * @param session 断线玩家会话。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：对局中断线时只向仍在线对手发送 gameOver。
         */
        synchronized void disconnect(Core.Session session) {
            if (finished) {
                return;
            }
            if (!started) {
                System.out.println("disconnect before gameStart: roomId=" + id
                        + ", disconnectedPlayerId=" + session.userId);
                finished = true;
                cancelAutoReady();
                cancelFirstHandWindow();
                cancelTurnTimer();
                return;
            }
            Core.Session winner = opponentOf(session);
            if (colorOf(session) == null || winner == session) {
                return;
            }
            finishGame("disconnect", winner, session, false);
        }

        /**
         * 构造调试用房间状态。
         *
         * @return serverStatus.rooms 数组中的一个房间状态。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：serverStatus 请求会调用该方法汇总所有房间。
         */
        synchronized Messages.RoomStatus status() {
            return new Messages.RoomStatus(id, red.userId, black.userId,
                    started, finished, turn.json());
        }

        /**
         * 返回指定玩家的对手。
         *
         * @param session 玩家会话。
         * @return 对手会话；传入非红方时按黑方处理返回红方。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：{@code Core.Session winner = opponentOf(loser);}
         */
        private Core.Session opponentOf(Core.Session session) {
            return session == red ? black : red;
        }

        /**
         * 开始对局并发送 gameStart。
         *
         * @throws RuntimeException 当发送消息或棋盘快照构造失败时可能抛出。
         * @apiNote 使用示例：双方 Ready 后由 {@link #ready(Core.Session)} 调用。
         */
        private void start() {
            cancelAutoReady();
            cancelFirstHandWindow();
            resolveFirstHand();
            started = true;
            turn = Color.RED;
            // 记录器在真实红黑确定后创建，保证棋谱中的 redPlayerId/blackPlayerId 与 gameStart 一致。
            recorder = GameRecorder.start(id, red.userId, black.userId);
            System.out.println("gameStart: roomId=" + id + ", redPlayerId=" + red.userId
                    + ", blackPlayerId=" + black.userId + ", currentTurn=" + turn.json());
            red.send(Messages.gameStart(red.userId, black.userId, Color.RED,
                    board.snapshot(), initialBoardMode));
            black.send(Messages.gameStart(red.userId, black.userId, Color.BLACK,
                    board.snapshot(), initialBoardMode));
            startTurnTimer();
        }

        /**
         * 在双方 Ready 后安排开局。
         *
         * <p>设计文档要求 Ready 后保留先手协商窗口，因此窗口为正数时延迟 gameStart；
         * 测试和联调可把窗口设为 0，沿用旧的立即开局行为。</p>
         *
         * @throws RuntimeException 当调度器拒绝任务或开局发送失败时可能抛出。
         * @apiNote 使用示例：双方 Ready 后调用；窗口结束后进入 {@link #start()}。
         */
        private void scheduleStartAfterFirstHandWindow() {
            if (started || finished || firstHandWindowTask != null) {
                return;
            }
            cancelAutoReady();
            if (firstHandWindowMs <= 0) {
                start();
                return;
            }
            System.out.println("first hand window opened: roomId=" + id
                    + ", durationMs=" + firstHandWindowMs);
            firstHandWindowTask = scheduler.schedule(() -> {
                synchronized (this) {
                    // 定时任务可能与断线/终局并发，二次检查避免窗口结束后误启动已关闭房间。
                    if (!finished && !started) {
                        start();
                    }
                }
            }, firstHandWindowMs, TimeUnit.MILLISECONDS);
        }

        /**
         * 取消尚未触发的先手窗口任务。
         *
         * <p>开局、断线或终局路径都可能走到这里；统一置空保证后续幂等判断稳定。</p>
         */
        private void cancelFirstHandWindow() {
            if (firstHandWindowTask != null) {
                firstHandWindowTask.cancel(false);
                firstHandWindowTask = null;
            }
        }

        /**
         * 取消自动 Ready 兜底任务。
         *
         * <p>双方已经 Ready、断线或终局后都不应再补 Ready；统一取消可避免旧任务延迟触发 gameStart。</p>
         */
        private void cancelAutoReady() {
            if (autoReadyTask != null) {
                autoReadyTask.cancel(false);
                autoReadyTask = null;
            }
        }

        /**
         * 根据双方先手意愿确定最终红黑。
         *
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：只有黑方请求先手且红方不请求时交换红黑。
         */
        private void resolveFirstHand() {
            boolean redWantsFirst = Boolean.TRUE.equals(redWannaFirst);
            boolean blackWantsFirst = Boolean.TRUE.equals(blackWannaFirst);
            if (!redWantsFirst && blackWantsFirst) {
                // 只在唯一一方请求先手时交换，双方都请求或都不请求则保持匹配队列默认顺序。
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

        /**
         * 合法走子后切换行棋方并重启计时。
         *
         * @throws RuntimeException 当调度器拒绝任务时可能抛出。
         * @apiNote 使用示例：合法 move 且未终局时调用。
         */
        private void switchTurnAfterLegalMove() {
            cancelTurnTimer();
            turn = turn.opposite();
            startTurnTimer();
        }

        /**
         * 为当前行棋方启动单步计时器。
         *
         * @throws RuntimeException 当调度器拒绝任务时可能抛出。
         * @apiNote 使用示例：gameStart 和合法走子切换回合后调用。
         */
        private void startTurnTimer() {
            if (finished || !started || turnTimeoutMs <= 0) {
                return;
            }
            Core.Session player = sessionOf(turn);
            // 捕获当前应走玩家，超时回调再核对 turn，防止旧任务误判新回合。
            turnTimer = scheduler.schedule(() -> onTurnTimeout(player), turnTimeoutMs, TimeUnit.MILLISECONDS);
        }

        /**
         * 取消当前回合计时器。
         *
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：合法走子、终局和未开局断线都会调用。
         */
        private void cancelTurnTimer() {
            if (turnTimer != null) {
                // false 表示不打断已开始执行的任务；任务内部会再次检查 finished 和当前玩家。
                turnTimer.cancel(false);
                turnTimer = null;
            }
        }

        /**
         * 处理单步超时。
         *
         * @param loser 调度时捕获的超时玩家。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：由 {@link #startTurnTimer()} 安排的定时任务调用。
         */
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

        /**
         * 统一处理非和棋终局。
         *
         * @param reason 终局原因。
         * @param winner 胜方会话。
         * @param loser 负方会话。
         * @param sendTimeout 是否先广播 timeout 消息。
         * @throws RuntimeException 当发送消息失败时可能由通道抛出；棋谱写失败会被捕获并记录。
         * @apiNote 使用示例：{@code finishGame("resign", winner, loser, false);}
         */
        private void finishGame(String reason, Core.Session winner, Core.Session loser, boolean sendTimeout) {
            if (finished) {
                return;
            }
            // 先设置 finished，确保后续发送消息、写棋谱或断线回调再次进入时不会重复 gameOver。
            finished = true;
            cancelAutoReady();
            cancelFirstHandWindow();
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
                    // 棋谱是诊断产物，写失败不能影响 gameOver 对客户端的交付。
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

        /**
         * 根据重复/无吃子判定决定是否终局。
         *
         * @param verdict 规则模块返回的判定。
         * @param mover 本步行棋玩家。
         * @return 已终局返回 {@code true}，否则返回 {@code false}。
         * @throws RuntimeException 当判定映射或终局发送失败时可能抛出。
         * @apiNote 使用示例：合法 move 应用后、切换回合前调用。
         */
        private boolean finishByRepetitionVerdict(RepetitionVerdict verdict, Core.Session mover) {
            RepetitionOutcome outcome = repetitionOutcome(verdict, colorOf(mover));
            if (outcome == null) {
                return false;
            }
            if (outcome.draw()) {
                finishDraw(outcome.reason());
            } else {
                // REPETITION_LOSS 的输家是本步实施重复行为的 mover，赢家是对手。
                finishGame(outcome.reason(), opponentOf(mover), mover, false);
            }
            return true;
        }

        /**
         * 统一处理和棋终局。
         *
         * @param reason 和棋原因。
         * @throws RuntimeException 当发送消息失败时可能由通道抛出；棋谱写失败会被捕获并记录。
         * @apiNote 使用示例：80 半步无吃子触发 {@code finishDraw("noCapture")}。
         */
        private void finishDraw(String reason) {
            if (finished) {
                return;
            }
            // 和棋同样走 finished 幂等保护，但 winnerId/loserId 必须保持 null。
            finished = true;
            cancelAutoReady();
            cancelFirstHandWindow();
            cancelTurnTimer();
            winnerId = null;
            loserId = null;
            finishReason = reason;
            if (recorder != null) {
                recorder.finish("draw", null, reason);
                try {
                    recorder.writeTo(recordsDir);
                } catch (Exception ex) {
                    // 记录失败只影响复盘材料，不应影响客户端收到终局消息。
                    System.err.println("write game record failed: roomId=" + id + ", error=" + ex.getMessage());
                }
            }
            broadcastOpen(Messages.gameOver("draw", reason, null));
            System.out.println("game over in " + id + ": reason=" + finishReason + ", winner=draw");
        }

        /**
         * 拒绝一手非法 move。
         *
         * @param session 发送非法 move 的玩家。
         * @param move 已解析 move。
         * @param code 错误码。
         * @param message 错误说明。
         * @throws RuntimeException 当通道发送失败时可能抛出。
         * @apiNote 使用示例：非当前玩家走子时发送 valid=false 和 2002。
         */
        private void rejectMove(Core.Session session, MoveMessage move, int code, String message) {
            session.send(Messages.moveResult(false, move.from, move.to, move.clientFlip, null, null));
            session.send(Messages.error(code, message));
            recordMove(colorOf(session), move, move.clientFlip, false, null, null);
            logMoveResult(session, move, false, move.clientFlip, null, null);
        }

        /**
         * 查询会话所属颜色。
         *
         * @param session 玩家会话。
         * @return 红方、黑方或 null。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：{@code Color mover = colorOf(session);}
         */
        private Color colorOf(Core.Session session) {
            if (session == red) {
                return Color.RED;
            }
            if (session == black) {
                return Color.BLACK;
            }
            return null;
        }

        /**
         * 根据颜色查找玩家会话。
         *
         * @param color 颜色。
         * @return 对应玩家会话。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：{@code Core.Session player = sessionOf(turn);}
         */
        private Core.Session sessionOf(Color color) {
            return color == Color.RED ? red : black;
        }

        /**
         * 计算某个接收者视角下的被吃棋子字段。
         *
         * @param result 服务端应用走子后的结果。
         * @param forMover 是否为行棋方视角。
         * @return capturedPiece 字符串；无吃子返回 null，被吃暗子对非吃方返回 {@code NULL}。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：{@code String captured = capturedPieceFor(result, true);}
         */
        private static String capturedPieceFor(Core.ApplyResult result, boolean forMover) {
            PieceType captured = result.capturedType();
            if (captured == null) {
                return null;
            }
            if (result.capturedWasHidden() && !forMover) {
                // 设计文档要求被吃暗子的真实类型只对吃方可见，非吃方使用 NULL 表示未知。
                return "NULL";
            }
            return captured.json();
        }

        /**
         * 将 moveResult 追加到棋谱记录器。
         *
         * @param mover 行棋方颜色，非法来源可能为 null。
         * @param move 已解析 move。
         * @param isFlip 服务端认定是否翻子。
         * @param valid 是否合法。
         * @param flipResult 翻子结果。
         * @param capturedPiece 被吃棋子字段。
         * @throws RuntimeException 当记录器处理坐标失败时可能抛出。
         * @apiNote 使用示例：合法和非法 moveResult 产生后都调用该方法。
         */
        private void recordMove(Color mover, MoveMessage move, boolean isFlip, boolean valid,
                                PieceType flipResult, String capturedPiece) {
            if (recorder != null) {
                recorder.recordMove(mover, move.from, move.to, isFlip, valid, flipResult, capturedPiece);
            }
        }

        /**
         * 打印 moveResult 联调日志。
         *
         * @param session 发送 move 的玩家。
         * @param move 已解析 move。
         * @param valid 是否合法。
         * @param isFlip 服务端认定是否翻子。
         * @param flipResult 翻子结果。
         * @param capturedPiece 被吃棋子字段。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：每次产生 moveResult 后输出一行排错日志。
         */
        private void logMoveResult(Core.Session session, MoveMessage move, boolean valid, boolean isFlip,
                                   PieceType flipResult, String capturedPiece) {
            System.out.println("moveResult: roomId=" + id + ", mover=" + session.userId
                    + ", from=" + coordText(move.from) + ", to=" + coordText(move.to)
                    + ", valid=" + valid + ", isFlip=" + isFlip
                    + ", flipResult=" + (flipResult == null ? "null" : flipResult.json())
                    + ", capturedPiece=" + capturedPiece);
        }

        /**
         * 将内部坐标转为协议坐标文本。
         *
         * @param coord 内部坐标。
         * @return 例如 {@code b2} 的坐标字符串。
         * @throws RuntimeException 当 coord 为空时抛出空指针异常。
         * @apiNote 使用示例：{@code String text = coordText(new Coord(1, 2));}
         */
        private static String coordText(Coord coord) {
            return String.valueOf((char) ('a' + coord.file())) + coord.rank();
        }

        /**
         * 向红黑双方发送消息，不检查连接状态。
         *
         * @param json 要发送的 JSON。
         * @throws RuntimeException 当任一通道发送失败时可能抛出。
         * @apiNote 使用示例：当前实现保留该方法供需要强制双发的场景扩展。
         */
        private void broadcast(String json) {
            red.send(json);
            black.send(json);
        }

        /**
         * 向仍在线的红黑双方发送消息。
         *
         * @param json 要发送的 JSON。
         * @throws RuntimeException 当开放通道发送失败时可能抛出。
         * @apiNote 使用示例：gameOver 和 timeout 使用该方法避免向已断线连接写入。
         */
        private void broadcastOpen(String json) {
            sendIfOpen(red, json);
            sendIfOpen(black, json);
        }

        /**
         * 如果玩家连接仍打开，则发送消息。
         *
         * @param session 目标玩家会话。
         * @param json 要发送的 JSON。
         * @throws RuntimeException 当通道发送失败时可能抛出。
         * @apiNote 使用示例：disconnect 终局只发送给在线对手。
         */
        private void sendIfOpen(Core.Session session, String json) {
            if (session.channel.isOpen()) {
                session.send(json);
            }
        }
    }
}
