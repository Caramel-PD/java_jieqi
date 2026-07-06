package jieqi.common;

/**
 * 行棋方颜色。编码与 JSON 名对齐设计文档 §2.3：0=红（先手、棋盘下方），1=黑（后手、上方）。
 */
public enum Color {
    RED(0, "red"),
    BLACK(1, "black");

    public final int code;
    private final String json;

    Color(int code, String json) {
        this.code = code;
        this.json = json;
    }

    /** JSON 层字符串（"red"/"black"）。发送用本值；解析大小写不敏感（§4.9）。 */
    public String json() {
        return json;
    }

    public Color opposite() {
        return this == RED ? BLACK : RED;
    }

    public static Color fromJson(String s) {
        for (Color c : values()) {
            if (c.json.equalsIgnoreCase(s)) return c;
        }
        throw new IllegalArgumentException("unknown color: " + s);
    }

    public static Color fromCode(int code) {
        for (Color c : values()) {
            if (c.code == code) return c;
        }
        throw new IllegalArgumentException("unknown color code: " + code);
    }
}
