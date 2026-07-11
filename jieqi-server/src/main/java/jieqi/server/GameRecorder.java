/*
 * 文件功能：记录单局揭棋对局过程，并在终局时写出机器可读 JSON 棋谱和人工可读 .jieqi 文本棋谱。
 * 所属模块：jieqi-server。
 * 使用场景：服务端完成 gameStart 后创建记录器，moveResult 后追加走子，gameOver 时落盘供复盘、联调和实验报告使用。
 */
package jieqi.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;

/**
 * 单局游戏的棋谱记录器。
 *
 * <p>记录器属于旁路组件：它只观察服务器已经裁定出的 moveResult 和 gameOver，
 * 不参与规则校验，也不改变房间状态。这样即使记录失败，也不会破坏服务端作为裁判的主流程。</p>
 *
 * <p>JSON 棋谱用于程序解析，.jieqi 文本棋谱用于人工复盘和实验报告；二者来自同一份内存记录，
 * 避免两种格式在终局原因、走子列表上出现分歧。</p>
 *
 * <p>使用示例：</p>
 * <pre>{@code
 * GameRecorder recorder = GameRecorder.start("room_1", "u1", "u2");
 * recorder.recordMove(Color.RED, from, to, true, true, PieceType.CANNON, null);
 * recorder.finish("red", "u1", "checkmate");
 * recorder.writeTo(Path.of("records"));
 * }</pre>
 */
final class GameRecorder {
    private static final int FORMAT_VERSION = 1;
    /** 棋谱用于机器读取和人工排错，保留 null 字段可以稳定 JSON schema。 */
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    /** 房间编号用于关联在线房间；唯一棋谱标识还会追加终局时间。 */
    private final String roomId;
    /** 红方玩家 ID，记录开局后的最终红黑分配结果。 */
    private final String redPlayerId;
    /** 黑方玩家 ID，记录开局后的最终红黑分配结果。 */
    private final String blackPlayerId;
    /** 开局时间戳，使用服务器本地时间保证不信任客户端时钟。 */
    private final long startTime;
    /** 逐步保存走子 JSON，避免额外 DTO 影响当前服务端最小实现。 */
    private final JsonArray moves = new JsonArray();
    /** 终局时间戳；0 表示尚未终局。 */
    private long endTime;
    /** 胜方颜色或 draw；与 gameOver.winner 保持一致。 */
    private String winner;
    /** 胜方玩家 ID；和棋时按协议扩展口径保持为 null。 */
    private String winnerId;
    /** 终局原因，例如 resign、timeout、draw_no_capture 或 repetition_loss。 */
    private String reason;
    /** 终局后由 roomId 与 endTime 组成，跨服务器重启仍可区分同名房间。 */
    private String recordId;
    /** 防止 finishGame 幂等调用时重复覆盖同一个棋谱文件。 */
    private boolean written;

    /**
     * 创建记录器实例。
     *
     * @param roomId 房间编号。
     * @param redPlayerId 红方玩家 ID。
     * @param blackPlayerId 黑方玩家 ID。
     * @param startTime 服务器记录的开局时间戳。
     * @throws RuntimeException 当前构造器不主动抛出异常。
     * @apiNote 使用示例：请优先使用 {@link #start(String, String, String)} 创建实例。
     */
    private GameRecorder(String roomId, String redPlayerId, String blackPlayerId, long startTime) {
        this.roomId = roomId;
        this.redPlayerId = redPlayerId;
        this.blackPlayerId = blackPlayerId;
        this.startTime = startTime;
    }

    /**
     * 在 gameStart 阶段创建棋谱记录器。
     *
     * @param roomId 房间编号。
     * @param redPlayerId 红方玩家 ID。
     * @param blackPlayerId 黑方玩家 ID。
     * @return 已记录 startTime 的新记录器。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：{@code GameRecorder.start(room.id, red.userId, black.userId);}
     */
    static GameRecorder start(String roomId, String redPlayerId, String blackPlayerId) {
        return new GameRecorder(roomId, redPlayerId, blackPlayerId, System.currentTimeMillis());
    }

    /**
     * 追加一步走子记录。
     *
     * @param mover 行棋方颜色；无法识别玩家时允许为 null，便于记录非法来源。
     * @param from 起点坐标。
     * @param to 终点坐标。
     * @param isFlip 服务端最终认定的本步是否翻子。
     * @param valid 本步是否为合法走子；非法走子也记录，便于联调排错。
     * @param flipResult 翻出的真实棋子类型；未翻子或非法时为 null。
     * @param capturedPiece 被吃棋子类型；无吃子或对接收方不可见时为 null 或 {@code NULL}。
     * @throws RuntimeException 当坐标对象为空导致访问失败时抛出，调用方应传入已解析坐标。
     * @apiNote 使用示例：{@code recorder.recordMove(Color.RED, from, to, true, true, PieceType.ROOK, null);}
     */
    synchronized void recordMove(Color mover, Coord from, Coord to, boolean isFlip, boolean valid,
                                 PieceType flipResult, String capturedPiece) {
        JsonObject move = new JsonObject();
        move.addProperty("moveNo", moves.size() + 1);
        addNullable(move, "mover", mover == null ? null : mover.json());
        move.addProperty("fromX", String.valueOf((char) ('a' + from.file())));
        move.addProperty("fromY", from.rank());
        move.addProperty("toX", String.valueOf((char) ('a' + to.file())));
        move.addProperty("toY", to.rank());
        move.addProperty("isFlip", isFlip);
        move.addProperty("valid", valid);
        // 明确写入 null 而不是省略字段，复盘器和报告脚本可以按固定字段读取。
        addNullable(move, "flipResult", flipResult == null ? null : flipResult.json());
        addNullable(move, "capturedPiece", capturedPiece);
        // 每步使用服务器时间戳，避免客户端伪造时间影响对局审计。
        move.addProperty("timestamp", System.currentTimeMillis());
        moves.add(move);
    }

    /**
     * 记录终局信息。
     *
     * @param winner 胜方颜色文本，和棋时为 {@code draw}。
     * @param winnerId 胜方玩家 ID；和棋时为 null。
     * @param reason 终局原因。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：{@code recorder.finish("black", "u2", "timeout");}
     */
    synchronized void finish(String winner, String winnerId, String reason) {
        if (endTime == 0) {
            // finishGame 可能被超时、断线、认输等路径重复触发；只保留首次终局事实。
            endTime = System.currentTimeMillis();
            recordId = roomId + "_" + endTime;
            this.winner = winner;
            this.winnerId = winnerId;
            this.reason = reason;
        }
    }

    /**
     * 将棋谱写入指定目录。
     *
     * @param recordsDir 棋谱目录；为 null 时跳过写文件，便于测试或禁用落盘。
     * @throws IOException 当创建目录或写文件失败时抛出，由房间终局逻辑记录错误并继续主流程。
     * @apiNote 使用示例：{@code recorder.writeTo(Path.of("records"));}
     */
    synchronized void writeTo(Path recordsDir) throws IOException {
        if (written || recordsDir == null) {
            return;
        }
        Files.createDirectories(recordsDir);
        if (recordId == null) {
            throw new IOException("game record has not finished");
        }
        Path jsonTarget = recordsDir.resolve(recordId + ".json");
        Path textTarget = recordsDir.resolve(recordId + ".jieqi");
        Path jsonTemp = Files.createTempFile(recordsDir, recordId + "_", ".json.tmp");
        Path textTemp = Files.createTempFile(recordsDir, recordId + "_", ".jieqi.tmp");
        boolean textPublished = false;
        try {
            Files.writeString(jsonTemp, GSON.toJson(toJson()), StandardCharsets.UTF_8);
            Files.writeString(textTemp, toText(), StandardCharsets.UTF_8);
            // JSON 是查询入口，最后发布可保证仓库看见 JSON 时配套文本已经完整存在。
            moveAtomically(textTemp, textTarget);
            textPublished = true;
            moveAtomically(jsonTemp, jsonTarget);
            written = true;
        } catch (IOException ex) {
            Files.deleteIfExists(jsonTemp);
            Files.deleteIfExists(textTemp);
            if (textPublished) {
                Files.deleteIfExists(textTarget);
            }
            throw ex;
        }
    }

    /**
     * 构造最终棋谱 JSON 对象。
     *
     * @return 包含房间、玩家、终局和 moves 数组的 JSON。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：仅由 {@link #writeTo(Path)} 在落盘前调用。
     */
    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        root.addProperty("recordId", recordId);
        root.addProperty("roomId", roomId);
        root.addProperty("redPlayerId", redPlayerId);
        root.addProperty("blackPlayerId", blackPlayerId);
        root.addProperty("startTime", startTime);
        root.addProperty("endTime", endTime);
        addNullable(root, "winner", winner);
        addNullable(root, "winnerId", winnerId);
        addNullable(root, "reason", reason);
        // deepCopy 避免调用方拿到 root 后间接修改内部 moves，保持记录器封装边界。
        root.add("moves", moves.deepCopy());
        return root;
    }

    /**
     * 在同一目录发布完整临时文件，文件系统不支持原子移动时安全回退。
     *
     * @param source 已完整写入的临时文件。
     * @param target 最终棋谱路径。
     * @throws IOException 移动失败时抛出，由终局入口记录日志但不影响 gameOver。
     */
    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target);
        }
    }

    /**
     * 构造人工可读的最小 .jieqi 文本棋谱。
     *
     * @return 包含对局双方、时间、结果、原因和逐步走子的文本内容。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：仅由 {@link #writeTo(Path)} 在终局落盘时调用。
     */
    private String toText() {
        StringBuilder text = new StringBuilder();
        text.append("Red: ").append(redPlayerId).append(System.lineSeparator());
        text.append("Black: ").append(blackPlayerId).append(System.lineSeparator());
        text.append("Start: ").append(startTime).append(System.lineSeparator());
        text.append("End: ").append(endTime).append(System.lineSeparator());
        text.append("Result: ").append(resultText()).append(System.lineSeparator());
        text.append("Reason: ").append(nullToDash(reason)).append(System.lineSeparator());
        text.append("Moves:").append(System.lineSeparator());
        for (int i = 0; i < moves.size(); i++) {
            appendTextMove(text, moves.get(i).getAsJsonObject());
        }
        return text.toString();
    }

    /**
     * 追加一行 .jieqi 走子文本。
     *
     * @param text 目标文本构造器。
     * @param move 已记录的单步 JSON；这里复用 JSON 记录，保证文本棋谱不重新推导走子事实。
     * @throws RuntimeException 当 move 缺少内部记录器保证存在的基础字段时抛出。
     * @apiNote 使用示例：{@code appendTextMove(builder, moves.get(0).getAsJsonObject());}
     */
    private static void appendTextMove(StringBuilder text, JsonObject move) {
        text.append(move.get("moveNo").getAsInt())
                .append(". ")
                .append(textValue(move, "mover"))
                .append(' ')
                .append(move.get("fromX").getAsString())
                .append(move.get("fromY").getAsInt())
                .append('-')
                .append(move.get("toX").getAsString())
                .append(move.get("toY").getAsInt());

        if (!move.get("valid").getAsBoolean()) {
            // 非法走子也落入文本棋谱，便于联调时复现客户端发出的异常输入。
            text.append(" invalid");
        }
        text.append(" isFlip=").append(move.get("isFlip").getAsBoolean());
        appendOptionalTextField(text, move, "flipResult");
        appendOptionalTextField(text, move, "capturedPiece");
        text.append(" timestamp=").append(move.get("timestamp").getAsLong())
                .append(System.lineSeparator());
    }

    /**
     * 追加可选走子属性。
     *
     * @param text 目标文本构造器。
     * @param move 单步走子 JSON。
     * @param key 字段名，例如 flipResult 或 capturedPiece。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：{@code appendOptionalTextField(builder, move, "flipResult");}
     */
    private static void appendOptionalTextField(StringBuilder text, JsonObject move, String key) {
        if (move.has(key) && !move.get(key).isJsonNull()) {
            // 文本棋谱只输出有实际含义的可选字段，保持人工阅读时足够紧凑。
            text.append(' ').append(key).append('=').append(move.get(key).getAsString());
        }
    }

    /**
     * 生成终局结果文本。
     *
     * @return 胜方颜色和玩家 ID，或和棋标记。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：{@code Result: black(u2)}。
     */
    private String resultText() {
        if (winner == null) {
            return "-";
        }
        if ("draw".equals(winner) || winnerId == null) {
            return winner;
        }
        return winner + "(" + winnerId + ")";
    }

    /**
     * 读取 JSON 文本字段，null 时输出占位符。
     *
     * @param object 源 JSON 对象。
     * @param key 字段名。
     * @return 字段文本；为空时返回 {@code -}。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：{@code textValue(move, "mover");}
     */
    private static String textValue(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            return "-";
        }
        return object.get(key).getAsString();
    }

    /**
     * 将空字符串字段转换为文本占位符。
     *
     * @param value 原始字段值。
     * @return 非空原值或 {@code -}。
     * @throws RuntimeException 当前实现不主动抛出异常。
     * @apiNote 使用示例：{@code nullToDash(reason);}
     */
    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }

    /**
     * 向 JSON 对象添加可空字符串字段。
     *
     * @param object 目标 JSON 对象。
     * @param key 字段名。
     * @param value 字段值；为 null 时写入 JSON null。
     * @throws RuntimeException 当 object 为空时由 Gson 抛出空指针异常。
     * @apiNote 使用示例：{@code addNullable(root, "winnerId", null);}
     */
    private static void addNullable(JsonObject object, String key, String value) {
        if (value == null) {
            // 使用显式 JsonNull，保证 serializeNulls 下输出稳定字段而不是缺字段。
            object.add(key, JsonNull.INSTANCE);
        } else {
            object.addProperty(key, value);
        }
    }
}
