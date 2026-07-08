package client;

import java.util.HashMap;
import java.util.Map;

public enum PieceType {
    KING("king"),
    ROOK("rook"),
    KNIGHT("knight"),
    CANNON("cannon"),
    PAWN("pawn"),
    GUARD("guard"),
    BISHOP("bishop");

    private final String value;
    private static final Map<String, PieceType> BY_JSON = new HashMap<>();

    static {
        for (PieceType pt : values()) {
            BY_JSON.put(pt.value, pt);
            BY_JSON.put(pt.value.toUpperCase(), pt);
            BY_JSON.put(pt.value.substring(0, 1).toUpperCase() + pt.value.substring(1), pt);
        }
    }

    PieceType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static PieceType fromJson(String s) {
        if (s == null) return null;
        return BY_JSON.get(s);
    }
}