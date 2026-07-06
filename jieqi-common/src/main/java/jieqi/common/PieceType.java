package jieqi.common;

/**
 * 棋子类型。内部编码 0–6 与作业 Move 类定义一致（设计文档 §2.3）：
 * 0=帅/将 1=车 2=马 3=炮 4=兵/卒 5=仕/士 6=相/象。
 * JSON 层用小写英文字符串；发送全小写，解析大小写不敏感（§2.3 / §4.9）。
 */
public enum PieceType {
    KING(0, "king"),
    ROOK(1, "rook"),
    KNIGHT(2, "knight"),
    CANNON(3, "cannon"),
    PAWN(4, "pawn"),
    GUARD(5, "guard"),
    BISHOP(6, "bishop");

    public final int code;
    private final String json;

    PieceType(int code, String json) {
        this.code = code;
        this.json = json;
    }

    public String json() {
        return json;
    }

    public static PieceType fromCode(int code) {
        for (PieceType t : values()) {
            if (t.code == code) return t;
        }
        throw new IllegalArgumentException("unknown piece code: " + code);
    }

    public static PieceType fromJson(String s) {
        for (PieceType t : values()) {
            if (t.json.equalsIgnoreCase(s)) return t;
        }
        throw new IllegalArgumentException("unknown piece: " + s);
    }
}
