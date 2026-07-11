package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.CellState;
import jieqi.rules.RuleEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic move ordering for search only; it never adds or removes legal moves.
 */
public final class MoveOrderer {

    private static final int KING_CAPTURE_SCORE = 1_000_000_000;
    private static final int CAPTURE_BASE = 100_000_000;
    private static final int CHECK_BONUS = 10_000_000;
    private static final int HIDDEN_MOVE_BONUS = 1_000_000;

    public List<Move> order(BoardSnapshot board, Color side, List<Move> legalMoves, BeliefState belief) {
        return order(board, side, legalMoves, belief, null);
    }

    public List<Move> order(
            BoardSnapshot board,
            Color side,
            List<Move> legalMoves,
            BeliefState belief,
            Move priorityMove) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(legalMoves, "legalMoves");
        Objects.requireNonNull(belief, "belief");

        List<Move> ordered = new ArrayList<>(legalMoves);
        ordered.sort(Comparator
                .comparingInt((Move move) -> score(board, side, move, belief)).reversed()
                .thenComparingInt(move -> move.from().file())
                .thenComparingInt(move -> move.from().rank())
                .thenComparingInt(move -> move.to().file())
                .thenComparingInt(move -> move.to().rank()));
        if (priorityMove != null) {
            int index = ordered.indexOf(priorityMove);
            if (index > 0) {
                ordered.remove(index);
                ordered.add(0, priorityMove);
            }
        }
        return ordered;
    }

    private int score(BoardSnapshot board, Color side, Move move, BeliefState belief) {
        CellState source = board.cellAt(move.from());
        CellState target = board.cellAt(move.to());
        if (target instanceof CellState.Revealed revealed && revealed.type() == PieceType.KING) {
            return KING_CAPTURE_SCORE;
        }

        int score = 0;
        if (!target.isEmpty()) {
            int victimValue = pieceValue(target, belief);
            int attackerValue = pieceValue(source, belief);
            score += CAPTURE_BASE + victimValue * 1_000 - attackerValue;
        }
        if (givesCheckAfter(board, side, move, source, belief)) {
            score += CHECK_BONUS;
        }
        if (source instanceof CellState.Hidden) {
            score += HIDDEN_MOVE_BONUS;
        }
        return score;
    }

    private boolean givesCheckAfter(BoardSnapshot board, Color side, Move move, CellState source, BeliefState belief) {
        PieceType flipAs = null;
        if (source instanceof CellState.Hidden hidden) {
            flipAs = mostLikelyType(belief, hidden.color());
        }
        try {
            return RuleEngine.givesCheck(board.apply(move.from(), move.to(), flipAs), side);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private PieceType mostLikelyType(BeliefState belief, Color side) {
        PieceType bestType = PieceType.PAWN;
        int bestCount = -1;
        for (PieceType type : belief.availableTypes(side)) {
            int count = belief.count(side, type);
            if (count > bestCount) {
                bestType = type;
                bestCount = count;
            }
        }
        return bestType;
    }

    private int pieceValue(CellState cell, BeliefState belief) {
        if (cell instanceof CellState.Revealed revealed) {
            return EvalWeights.pieceValue(revealed.type());
        }
        if (cell instanceof CellState.Hidden hidden) {
            return belief.expectedValue(hidden.color());
        }
        return 0;
    }
}
