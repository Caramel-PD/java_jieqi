package jieqi.common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Map;

/**
 * JSON 宽容解析工具（设计文档 §4.9 宽进严出——本类只负责"宽进"侧；
 * "严出"由消息构造方保证字段名与类型严格照公共接口字面）。
 *
 * <p>宽容点：键查找大小写不敏感；数值字段容忍字符串数字（"2"）；布尔容忍字符串（"true"）；
 * 多余字段天然容忍（按键取值）。解析失败抛 IllegalArgumentException（上层映射错误码 4001）。
 */
public final class Json {

    private Json() {}

    /** 解析为 JSON 对象；非对象或语法错误抛 IllegalArgumentException（→ 4001）。 */
    public static JsonObject parseObject(String text) {
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) {
                throw new IllegalArgumentException("not a JSON object");
            }
            return e.getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("bad JSON: " + ex.getMessage(), ex);
        }
    }

    /** messageType（小写化）；缺失返回 null（上层按 4001 处理）。 */
    public static String messageType(JsonObject o) {
        String t = optString(o, "messageType", null);
        return t == null ? null : t.trim().toLowerCase();
    }

    /** 键大小写不敏感取原始元素；无则 null。 */
    public static JsonElement get(JsonObject o, String key) {
        JsonElement direct = o.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    public static String optString(JsonObject o, String key, String def) {
        JsonElement e = get(o, key);
        if (e == null || e.isJsonNull()) return def;
        if (e.isJsonPrimitive()) return e.getAsString();
        return def;
    }

    /** 容忍字符串数字（"2"）。 */
    public static int optInt(JsonObject o, String key, int def) {
        JsonElement e = get(o, key);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isNumber()) return p.getAsInt();
            return Integer.parseInt(p.getAsString().trim());
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /** 容忍字符串布尔（"true"/"false"，大小写不敏感）。 */
    public static boolean optBool(JsonObject o, String key, boolean def) {
        JsonElement e = get(o, key);
        if (e == null || !e.isJsonPrimitive()) return def;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        String s = p.getAsString().trim();
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        return def;
    }

    public static JsonObject optObject(JsonObject o, String key) {
        JsonElement e = get(o, key);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }
}
