package jieqi.ai;

import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.common.PieceType;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Small deterministic evaluator for baseline agents.
 */
public final class PositionEvaluator {

    private static final Map<PieceType, Integer> PIECE_VALUES = new EnumMap<>(PieceType.class);

    /*
     * Hidden pieces never include kings. The unknown value is the rounded average
     * of the initial hidden pool: (2R + 2N + 2C + 5P + 2G + 2B) / 15 = 227.
     * This keeps GreedyAgent from reading hidden true identities.
     */
    public static final int UNKNOWN_HIDDEN_VALUE = 227;

    static {
        PIECE_VALUES.put(PieceType.KING, 10_000);
        PIECE_VALUES.put(PieceType.ROOK, 500);
        PIECE_VALUES.put(PieceType.CANNON, 350);
        PIECE_VALUES.put(PieceType.KNIGHT, 300);
        PIECE_VALUES.put(PieceType.GUARD, 150);
        PIECE_VALUES.put(PieceType.BISHOP, 150);
        PIECE_VALUES.put(PieceType.PAWN, 100);
    }

    public int pieceValue(PieceType type) {
        return PIECE_VALUES.get(Objects.requireNonNull(type, "type"));
    }

    public int captureValue(PlayerView view, Move move) {
        Objects.requireNonNull(move, "move");
        return targetValue(view, move.to());
    }

    public int targetValue(PlayerView view, Coord target) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(target, "target");
        if (!view.isOccupied(target)) {
            return 0;
        }
        if (view.isHidden(target)) {
            return UNKNOWN_HIDDEN_VALUE;
        }
        return view.revealedPieceTypeAt(target)
                .map(this::pieceValue)
                .orElse(0);
    }
}
