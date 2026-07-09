package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.CellState;
import jieqi.rules.RuleEngine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Minimal L2 search agent: fixed-depth negamax with alpha-beta pruning.
 */
public final class ExpectiAgent implements Agent {

    public static final int DEFAULT_MAX_DEPTH = 3;

    private static final int WIN_SCORE = EvalWeights.KING_VALUE * 100;
    private static final int INF = WIN_SCORE * 10;

    private final int maxDepth;
    private final PositionEvaluator evaluator;

    public ExpectiAgent() {
        this(DEFAULT_MAX_DEPTH);
    }

    public ExpectiAgent(int maxDepth) {
        this(maxDepth, new PositionEvaluator());
    }

    public ExpectiAgent(int maxDepth, PositionEvaluator evaluator) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        this.maxDepth = maxDepth;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    @Override
    public Optional<Move> selectMove(PlayerView view, TimeBudget budget) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(budget, "budget");

        List<Move> legalMoves = view.legalMoves();
        if (legalMoves.isEmpty()) {
            return Optional.empty();
        }

        BoardSnapshot board = view.informationBoard();
        Color side = view.sideToMove();
        Move bestMove = legalMoves.get(0);
        int bestScore = Integer.MIN_VALUE;
        int alpha = -INF;

        for (Move move : legalMoves) {
            if (capturesKing(board, move)) {
                return Optional.of(move);
            }
            SearchState next = apply(board, side, move, BeliefState.initial().copy());
            int score = -negamax(next.board(), side.opposite(), maxDepth - 1, -INF, -alpha, next.belief());
            if (score > bestScore) {
                bestMove = move;
                bestScore = score;
            }
            alpha = Math.max(alpha, bestScore);
        }
        return Optional.of(bestMove);
    }

    private int negamax(BoardSnapshot board, Color side, int depth, int alpha, int beta, BeliefState belief) {
        if (RuleEngine.isKingCaptured(board, side)) {
            return -WIN_SCORE - depth;
        }
        if (RuleEngine.isKingCaptured(board, side.opposite())) {
            return WIN_SCORE + depth;
        }
        if (depth == 0) {
            return evaluate(board, side, belief);
        }

        List<Move> legalMoves = RuleEngine.generateLegalMoves(board, side);
        if (legalMoves.isEmpty()) {
            return evaluate(board, side, belief);
        }

        int best = -INF;
        for (Move move : legalMoves) {
            if (capturesKing(board, move)) {
                return WIN_SCORE + depth;
            }
            SearchState next = apply(board, side, move, belief.copy());
            int score = -negamax(next.board(), side.opposite(), depth - 1, -beta, -alpha, next.belief());
            if (score > best) {
                best = score;
            }
            alpha = Math.max(alpha, score);
            if (alpha >= beta) {
                break;
            }
        }
        return best;
    }

    private int evaluate(BoardSnapshot board, Color side, BeliefState belief) {
        int material = material(board, side, belief);
        int mobility = evaluator.mobilityScore(board, side) - evaluator.mobilityScore(board, side.opposite());
        return material * 10 + mobility;
    }

    private int material(BoardSnapshot board, Color perspective, BeliefState belief) {
        int score = 0;
        for (int rank = 0; rank < 10; rank++) {
            for (int file = 0; file < 9; file++) {
                CellState cell = board.cellAt(new Coord(file, rank));
                if (cell.isEmpty()) {
                    continue;
                }
                int value = valueOf(cell, belief);
                score += cell.color() == perspective ? value : -value;
            }
        }
        return score;
    }

    private int valueOf(CellState cell, BeliefState belief) {
        if (cell instanceof CellState.Revealed revealed) {
            return EvalWeights.pieceValue(revealed.type());
        }
        return belief.expectedValue(cell.color());
    }

    private SearchState apply(BoardSnapshot board, Color side, Move move, BeliefState belief) {
        CellState source = board.cellAt(move.from());
        CellState target = board.cellAt(move.to());
        PieceType flipAs = null;
        if (source instanceof CellState.Hidden) {
            flipAs = proxyRevealType(belief, side);
            if (belief.count(side, flipAs) > 0) {
                belief.recordKnownReveal(side, flipAs);
            }
        }
        if (target instanceof CellState.Hidden hiddenTarget) {
            belief.recordUnknownRemoval(hiddenTarget.color());
        }
        return new SearchState(board.apply(move.from(), move.to(), flipAs), belief);
    }

    private PieceType proxyRevealType(BeliefState belief, Color side) {
        PieceType bestType = PieceType.PAWN;
        int bestCount = -1;
        for (PieceType type : PieceType.values()) {
            if (type == PieceType.KING) {
                continue;
            }
            int count = belief.count(side, type);
            if (count > bestCount) {
                bestType = type;
                bestCount = count;
            }
        }
        if (bestCount <= 0) {
            return PieceType.PAWN;
        }
        return bestType;
    }

    private boolean capturesKing(BoardSnapshot board, Move move) {
        return board.cellAt(move.to()) instanceof CellState.Revealed revealed
                && revealed.type() == PieceType.KING;
    }

    private record SearchState(BoardSnapshot board, BeliefState belief) {
    }
}
