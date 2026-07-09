package jieqi.ai;

import jieqi.common.PieceType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Shared evaluation constants from the design document.
 */
public final class EvalWeights {

    private static final Map<PieceType, Integer> PIECE_VALUES = new EnumMap<>(PieceType.class);

    public static final int KING_VALUE = 10_000;

    static {
        PIECE_VALUES.put(PieceType.KING, KING_VALUE);
        PIECE_VALUES.put(PieceType.ROOK, 600);
        PIECE_VALUES.put(PieceType.KNIGHT, 270);
        PIECE_VALUES.put(PieceType.CANNON, 285);
        PIECE_VALUES.put(PieceType.PAWN, 30);
        PIECE_VALUES.put(PieceType.GUARD, 150);
        PIECE_VALUES.put(PieceType.BISHOP, 150);
    }

    private EvalWeights() {
    }

    public static int pieceValue(PieceType type) {
        return PIECE_VALUES.get(Objects.requireNonNull(type, "type"));
    }
}
