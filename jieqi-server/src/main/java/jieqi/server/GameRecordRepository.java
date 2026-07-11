/*
 * 文件功能：从服务端棋谱目录只读加载已落盘的 JSON 棋谱。
 * 所属模块：jieqi-server。
 * 使用场景：已登录客户端查询历史棋谱列表或单局详情，不参与在线对局状态管理。
 */
package jieqi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 已落盘棋谱的只读仓库。
 *
 * <p>仓库只扫描配置目录中的最终普通 {@code .json} 文件，并根据解析后的 recordId 查询，
 * 不用客户端输入拼接路径。这样既不会暴露进行中的 GameRecorder，也避免路径穿越读取目录外文件。</p>
 */
final class GameRecordRepository {
    private final Path recordsDir;

    /**
     * 创建棋谱仓库。
     *
     * @param recordsDir 已终局棋谱目录；为 null 表示禁用落盘和查询。
     * @throws RuntimeException 当前构造方法只保存路径，不访问磁盘。
     * @apiNote 使用示例：{@code new GameRecordRepository(config.recordsDir)}。
     */
    GameRecordRepository(Path recordsDir) {
        this.recordsDir = recordsDir;
    }

    /**
     * 查询有效棋谱摘要并执行稳定排序和分页。
     *
     * @param offset 从排序后结果的第几个元素开始。
     * @param limit 最多返回多少条。
     * @return 包含分页前总数和当前页摘要的查询结果。
     * @throws IllegalArgumentException offset 或 limit 不符合调用约定时抛出。
     * @apiNote 协议层已限制 limit 最大为 100。
     */
    QueryResult query(int offset, int limit) {
        if (offset < 0 || limit <= 0) {
            throw new IllegalArgumentException("invalid pagination");
        }
        List<JsonObject> records = loadValidRecords();
        records.sort(Comparator
                .comparingLong(GameRecordRepository::endTime).reversed()
                .thenComparing(GameRecordRepository::recordId, Comparator.reverseOrder()));

        int total = records.size();
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<JsonObject> summaries = new ArrayList<>(to - from);
        for (JsonObject record : records.subList(from, to)) {
            summaries.add(toSummary(record));
        }
        return new QueryResult(total, summaries);
    }

    /**
     * 按解析后的唯一棋谱标识查找完整记录。
     *
     * @param requestedRecordId 客户端请求的棋谱标识，仅用于和已解析内容比较。
     * @return 找到时返回原始 JSON 对象的副本，否则为空。
     * @throws RuntimeException 单个文件解析异常会在内部隔离，不向调用方传播。
     * @apiNote 即使传入 {@code ../x}，本方法也不会据此构造文件路径。
     */
    Optional<JsonObject> findByRecordId(String requestedRecordId) {
        for (JsonObject record : loadValidRecords()) {
            if (requestedRecordId.equals(recordId(record))) {
                return Optional.of(record.deepCopy());
            }
        }
        return Optional.empty();
    }

    /**
     * 扫描并解析目录中的有效 JSON 棋谱。
     *
     * @return 所有通过顶层字段校验的记录；目录不可用时返回空列表。
     * @throws RuntimeException 目录级 I/O 错误会被隔离并转换为空结果。
     * @apiNote 损坏文件只记录日志并跳过，确保其他棋谱仍可查询。
     */
    private List<JsonObject> loadValidRecords() {
        List<JsonObject> records = new ArrayList<>();
        Set<String> recordIds = new HashSet<>();
        if (recordsDir == null || !Files.isDirectory(recordsDir, LinkOption.NOFOLLOW_LINKS)) {
            return records;
        }
        try (Stream<Path> files = Files.list(recordsDir)) {
            files.filter(GameRecordRepository::isJsonRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> parseRecord(path).ifPresent(record -> {
                        String id = recordId(record);
                        if (recordIds.add(id)) {
                            records.add(record);
                        } else {
                            // 重复标识会让详情查询不确定，因此稳定保留文件名排序后的第一份。
                            System.err.println("skip duplicate game recordId " + id
                                    + " from " + path.getFileName());
                        }
                    }));
        } catch (IOException ex) {
            System.err.println("game record directory read failed: " + ex.getMessage());
        }
        return records;
    }

    private static boolean isJsonRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && path.getFileName().toString().toLowerCase().endsWith(".json");
    }

    private static Optional<JsonObject> parseRecord(Path path) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject() || !isValidRecord(parsed.getAsJsonObject())) {
                throw new IllegalArgumentException("missing required record fields");
            }
            JsonObject record = parsed.getAsJsonObject();
            if (record.has("recordId") && !isString(record, "recordId")) {
                throw new IllegalArgumentException("invalid recordId");
            }
            if (!record.has("recordId")) {
                // 旧棋谱以真实文件名作为兼容标识，只补充响应对象，不回写历史文件。
                record.addProperty("recordId", stripJsonSuffix(path.getFileName().toString()));
            }
            return Optional.of(record);
        } catch (Exception ex) {
            // 文件级隔离是查询接口的安全边界，不能让一份损坏棋谱阻断整个列表或连接。
            System.err.println("skip invalid game record " + path.getFileName() + ": " + ex.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isValidRecord(JsonObject record) {
        return isString(record, "roomId")
                && isString(record, "redPlayerId")
                && isString(record, "blackPlayerId")
                && isNumber(record, "startTime")
                && isNumber(record, "endTime")
                && isString(record, "winner")
                && isString(record, "reason")
                && record.has("moves") && record.get("moves").isJsonArray();
    }

    private static boolean isString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isString()
                && !object.get(key).getAsString().isBlank();
    }

    private static boolean isNumber(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive()
                && object.get(key).getAsJsonPrimitive().isNumber();
    }

    private static JsonObject toSummary(JsonObject record) {
        JsonObject summary = new JsonObject();
        copy(record, summary, "recordId");
        copy(record, summary, "roomId");
        copy(record, summary, "redPlayerId");
        copy(record, summary, "blackPlayerId");
        copy(record, summary, "startTime");
        copy(record, summary, "endTime");
        copy(record, summary, "winner");
        if (record.has("winnerId") && !record.get("winnerId").isJsonNull()) {
            copy(record, summary, "winnerId");
        } else {
            summary.add("winnerId", null);
        }
        copy(record, summary, "reason");
        summary.addProperty("moveCount", record.getAsJsonArray("moves").size());
        return summary;
    }

    private static void copy(JsonObject source, JsonObject target, String key) {
        target.add(key, source.get(key).deepCopy());
    }

    private static long endTime(JsonObject record) {
        return record.get("endTime").getAsLong();
    }

    private static String recordId(JsonObject record) {
        return record.get("recordId").getAsString();
    }

    private static String stripJsonSuffix(String fileName) {
        return fileName.substring(0, fileName.length() - ".json".length());
    }

    /**
     * 棋谱列表查询结果。
     *
     * @param total 分页前有效棋谱总数。
     * @param records 当前页摘要。
     */
    record QueryResult(int total, List<JsonObject> records) {}
}
