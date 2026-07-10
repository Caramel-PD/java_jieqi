/*
 * 文件功能：服务端基础配置、会话、账户存储和权威棋盘基础构件。
 * 所属模块：jieqi-server。
 * 使用场景：ProtocolServer、ServerMain 与测试代码共享本文件中的配置读取、连接抽象和棋盘应用能力。
 */
package jieqi.server;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.BoardText;
import jieqi.rules.CellState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务器基础构件（设计文档 §6 / §9.1 / §11.3）。单文件聚合小类，降低阅读跳转成本：
 * ServerConfig（配置总表）、ClientChannel（传输抽象，测试可注入 Fake）、Session、
 * AccountStore（内存 + users.json 落盘）、HiddenPool（抽池）、ServerBoard（落子流水线 §5.3(b)）。
 */
public final class Core {

    private Core() {}

    // ------------------------------------------------------------------
    /** 配置项总表（§11.3），环境变量覆盖。 */
    public static final class ServerConfig {
        public int port = 8887;                       // Q16 / JIEQI_PORT
        public long turnTimeoutMs = 65_000;           // Q14/Q31
        public long firstHandWindowMs = 10_000;       // 公共接口注记
        public int repetitionLimit = 6;               // §2.10
        public int repetitionMinRepeats = 3;
        public int noCaptureLimitHalfMoves = 80;      // Q3
        public String initialBoardMode = "virtual";   // virtual / omit（§4.4）
        public long autoReadyAfterMs = 0;             // 0=关（§6.2 联调兜底）
        public boolean autoRegisterOnLogin = true;    // 联调友好：未注册即登录视为注册（可关）
        public Path recordsDir = Path.of("records");  // §9.2
        public Path usersFile = null;                 // null=纯内存（测试）；生产 users.json

        /**
         * 从当前进程环境变量创建服务端配置。
         *
         * @return 应用环境变量覆盖后的服务端配置。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：{@code Core.ServerConfig config = Core.ServerConfig.fromEnv();}
         */
        public static ServerConfig fromEnv() {
            return fromEnv(System.getenv());
        }

        /**
         * 从指定环境变量表创建配置，生产路径传入真实环境，测试路径传入可控 Map。
         *
         * @param env 环境变量键值表，缺失或非法数值会回落到默认值。
         * @return 应用环境变量覆盖后的服务端配置。
         * @throws RuntimeException 当前实现不主动抛出异常。
         * @apiNote 使用示例：{@code ServerConfig.fromEnv(Map.of("JIEQI_AUTO_READY_AFTER_MS", "50"))}。
         */
        static ServerConfig fromEnv(Map<String, String> env) {
            ServerConfig c = new ServerConfig();
            String home = env.getOrDefault("JIEQI_HOME", ".");
            // 环境变量只覆盖启动期配置，不改变测试里手动 new ServerConfig 后逐项赋值的用法。
            c.port = envInt(env, "JIEQI_PORT", c.port);
            c.turnTimeoutMs = envInt(env, "JIEQI_TURN_TIMEOUT_MS", (int) c.turnTimeoutMs);
            c.firstHandWindowMs = envInt(env, "JIEQI_FIRSTHAND_WINDOW_MS", (int) c.firstHandWindowMs);
            c.autoReadyAfterMs = envInt(env, "JIEQI_AUTO_READY_AFTER_MS", (int) c.autoReadyAfterMs);
            c.repetitionLimit = envInt(env, "JIEQI_REPETITION_LIMIT", c.repetitionLimit);
            c.repetitionMinRepeats = envInt(env, "JIEQI_REPETITION_MIN_REPEATS", c.repetitionMinRepeats);
            c.noCaptureLimitHalfMoves = envInt(env, "JIEQI_NO_CAPTURE_LIMIT_HALF_MOVES",
                    c.noCaptureLimitHalfMoves);
            c.initialBoardMode = envString(env, "JIEQI_INITIAL_BOARD_MODE", c.initialBoardMode);
            c.autoRegisterOnLogin = !"false".equalsIgnoreCase(env.get("JIEQI_AUTO_REGISTER"));
            // 数据文件默认落在 JIEQI_HOME 下；显式路径优先，便于 CI/联调使用临时目录隔离数据。
            c.recordsDir = envPath(env, "JIEQI_RECORDS_DIR", Path.of(home, "records"));
            c.usersFile = envPath(env, "JIEQI_USERS_FILE", Path.of(home, "users.json"));
            return c;
        }

        private static int envInt(String key, int def) {
            return envInt(System.getenv(), key, def);
        }

        private static int envInt(Map<String, String> env, String key, int def) {
            String v = env.get(key);
            if (v == null || v.isBlank()) return def;
            try {
                return Integer.parseInt(v.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }

        private static String envString(Map<String, String> env, String key, String def) {
            String v = env.get(key);
            return v == null || v.isBlank() ? def : v.trim();
        }

        private static Path envPath(Map<String, String> env, String key, Path def) {
            String v = env.get(key);
            return v == null || v.isBlank() ? def : Path.of(v.trim());
        }
    }

    // ------------------------------------------------------------------
    /** 传输抽象：真实实现包 WebSocket 连接；测试注入 FakeChannel。 */
    public interface ClientChannel {
        void send(String json);

        /** 主动关闭连接（Q27 超限帧等）。关闭后传输层应回调 Lobby.onClosed。 */
        void closeConnection();

        boolean isOpen();

        String remote();
    }

    // ------------------------------------------------------------------
    public enum SessionState { CONNECTED, AUTHED, MATCHING, IN_ROOM }

    public static final class Session {
        public final ClientChannel channel;
        public volatile SessionState state = SessionState.CONNECTED;
        public volatile String userId;
        public volatile String nickname;
        /** P1 待实现:类型将改为 GameRoom(房间状态机,设计 §6.2)。当前用 Object 占位以便半成品独立编译。 */
        public volatile Object room;

        public Session(ClientChannel channel) {
            this.channel = channel;
        }

        public void send(String json) {
            channel.send(json);
        }
    }

    // ------------------------------------------------------------------
    /** 账户（§9.1）：内存 Map + users.json（userId → sha256(password), nickname）。 */
    public static final class AccountStore {
        private final Map<String, String[]> users = new ConcurrentHashMap<>();
        private final Path file;

        public AccountStore(Path file) {
            this.file = file;
            load();
        }

        public synchronized boolean register(String userId, String password, String nickname) {
            if (userId == null || userId.isBlank() || password == null) return false;
            if (users.containsKey(userId)) return false;
            users.put(userId, new String[]{sha256(password), nickname == null ? userId : nickname});
            save();
            return true;
        }

        /** 登录校验；autoRegister 时未知用户即时注册。返回 nickname 或 null（失败 → 1001）。 */
        public synchronized String login(String userId, String password, boolean autoRegister) {
            if (userId == null || userId.isBlank() || password == null) return null;
            String[] rec = users.get(userId);
            if (rec == null) {
                if (!autoRegister) return null;
                register(userId, password, userId);
                rec = users.get(userId);
            }
            return sha256(password).equals(rec[0]) ? rec[1] : null;
        }

        static String sha256(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder(64);
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void load() {
            if (file == null || !Files.exists(file)) return;
            try {
                com.google.gson.JsonObject root =
                        jieqi.common.Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                for (var e : root.entrySet()) {
                    com.google.gson.JsonObject u = e.getValue().getAsJsonObject();
                    users.put(e.getKey(), new String[]{
                            u.get("pw").getAsString(), u.get("nick").getAsString()});
                }
            } catch (Exception ignore) {
                // 损坏的 users.json 不应阻止服务器启动
            }
        }

        public synchronized void save() {
            if (file == null) return;
            try {
                com.google.gson.JsonObject root = new com.google.gson.JsonObject();
                for (var e : users.entrySet()) {
                    com.google.gson.JsonObject u = new com.google.gson.JsonObject();
                    u.addProperty("pw", e.getValue()[0]);
                    u.addProperty("nick", e.getValue()[1]);
                    root.add(e.getKey(), u);
                }
                if (file.getParent() != null) Files.createDirectories(file.getParent());
                Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignore) {
            }
        }
    }

    // ------------------------------------------------------------------
    /**
     * 单方暗子剩余池（Q9 惰性抽池的服务器真相）：初始 {车2,马2,炮2,兵5,士2,象2} 共 15。
     * 揭示（翻明 / 被吃暗子）时等概率无放回抽取。
     */
    public static final class HiddenPool {
        private final EnumMap<PieceType, Integer> counts = new EnumMap<>(PieceType.class);
        private int size;

        public static HiddenPool initial() {
            HiddenPool p = new HiddenPool();
            p.counts.put(PieceType.ROOK, 2);
            p.counts.put(PieceType.KNIGHT, 2);
            p.counts.put(PieceType.CANNON, 2);
            p.counts.put(PieceType.PAWN, 5);
            p.counts.put(PieceType.GUARD, 2);
            p.counts.put(PieceType.BISHOP, 2);
            p.size = 15;
            return p;
        }

        public synchronized PieceType drawRandom(Random rng) {
            if (size <= 0) throw new IllegalStateException("pool exhausted");
            int k = rng.nextInt(size);
            for (var e : counts.entrySet()) {
                k -= e.getValue();
                if (k < 0) {
                    e.setValue(e.getValue() - 1);
                    size--;
                    return e.getKey();
                }
            }
            throw new IllegalStateException("unreachable");
        }

        public synchronized int size() {
            return size;
        }

        public synchronized int count(PieceType t) {
            return counts.getOrDefault(t, 0);
        }

        public synchronized Map<PieceType, Integer> snapshot() {
            return new HashMap<>(counts);
        }
    }

    // ------------------------------------------------------------------
    /** 一步落子的完整结果（供记谱、差异化广播、终局检测）。 */
    public record ApplyResult(
            boolean isFlip,
            PieceType flipType,          // isFlip 时非空
            PieceType capturedType,      // 无吃子为 null；吃暗子为抽取揭示的真实类型
            boolean capturedWasHidden,   // Q1/Q13 差异化广播依据
            boolean kingCaptured) {}

    // ------------------------------------------------------------------
    /** 服务器权威棋盘（§3.4-1）：唯一真相 = 快照 + 双池；apply 即 §5.3(b) 流水线。 */
    public static final class ServerBoard {
        private BoardSnapshot board = BoardText.board(BoardText.INITIAL);
        public final HiddenPool redPool = HiddenPool.initial();
        public final HiddenPool blackPool = HiddenPool.initial();

        public BoardSnapshot snapshot() {
            return board;
        }

        /** 仅测试注入自定局面（§10.3 长将/兵卒长捉等场景脚本）。 */
        void testSetBoard(BoardSnapshot b) {
            this.board = b;
        }

        public HiddenPool poolOf(Color c) {
            return c == Color.RED ? redPool : blackPool;
        }

        /** 前置条件：着法已通过 RuleEngine.validate。 */
        public ApplyResult apply(Color mover, Coord from, Coord to, Random rng) {
            CellState moving = board.cellAt(from);
            CellState target = board.cellAt(to);
            boolean movingDark = moving instanceof CellState.Hidden;

            PieceType captured = null;
            boolean capturedWasHidden = false;
            boolean kingCaptured = false;
            if (target instanceof CellState.Revealed rev) {
                captured = rev.type();
                kingCaptured = captured == PieceType.KING;
            } else if (target instanceof CellState.Hidden hid) {
                captured = poolOf(hid.color()).drawRandom(rng);   // 为吃方揭示；victim 池同步扣减（Q13）
                capturedWasHidden = true;
            }

            PieceType flip = movingDark ? poolOf(mover).drawRandom(rng) : null;   // Q9
            board = board.apply(from, to, flip);
            return new ApplyResult(movingDark, flip, captured, capturedWasHidden, kingCaptured);
        }
    }
}
