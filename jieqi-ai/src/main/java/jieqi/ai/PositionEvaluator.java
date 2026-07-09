package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.CellState;
import jieqi.rules.RuleEngine;

import java.util.Objects;

/**
 * Small deterministic evaluator for baseline agents.
 */
public final class PositionEvaluator {

    /*
     * Hidden pieces never include kings. The unknown value follows design §8.2:
     * (2R + 2N + 2C + 5P + 2G + 2B) / 15 = 204 using EvalWeights.
     * This keeps GreedyAgent from reading hidden true identities.
     */
    public static final int UNKNOWN_HIDDEN_VALUE = BeliefState.initial().expectedValue(Color.RED);

    public int pieceValue(PieceType type) {
        return EvalWeights.pieceValue(Objects.requireNonNull(type, "type"));
    }

    public int captureValue(PlayerView view, Move move) {
        Objects.requireNonNull(move, "move");
        return targetValue(view, move.to());
    }

    public int evaluateAfterMove(PlayerView view, Move move) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(move, "move");

        BoardSnapshot board = view.informationBoard();
        CellState source = board.cellAt(move.from());
        // Unknown hidden movers are approximated as pawns for mobility; no hidden pool truth is read.
        PieceType flipAs = source instanceof CellState.Hidden ? PieceType.PAWN : null;
        BoardSnapshot after = board.apply(move.from(), move.to(), flipAs);

        int captureScore = captureValue(view, move) * 100;
        int flipScore = source instanceof CellState.Hidden ? 25 : 0;
        int mobilityScore = mobilityScore(after, view.sideToMove()) - mobilityScore(after, view.sideToMove().opposite());
        return captureScore + flipScore + mobilityScore;
    }

    public int materialScore(PlayerView view, Color perspective) {
        Objects.requireNonNull(view, "view");
        return materialScore(view.informationBoard(), perspective);
    }

    public int materialScore(BoardSnapshot board, Color perspective) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(perspective, "perspective");

        int score = 0;
        for (int rank = 0; rank < 10; rank++) {
            for (int file = 0; file < 9; file++) {
                CellState cell = board.cellAt(new Coord(file, rank));
                if (cell.isEmpty()) {
                    continue;
                }
                int value = cell instanceof CellState.Revealed revealed
                        ? pieceValue(revealed.type())
                        : UNKNOWN_HIDDEN_VALUE;
                score += cell.color() == perspective ? value : -value;
            }
        }
        return score;
    }

    public int mobilityScore(PlayerView view) {
        Objects.requireNonNull(view, "view");
        return view.legalMoves().size();
    }

    public int mobilityScore(BoardSnapshot board, Color side) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(side, "side");
        return RuleEngine.generateLegalMoves(board, side).size();
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
