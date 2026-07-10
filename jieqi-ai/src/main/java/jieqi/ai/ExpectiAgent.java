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
 * Iterative-deepening Expecti/Negamax agent.
 *
 * <p>Ordinary decision nodes use alpha-beta pruning. Hidden-piece move nodes are chance nodes:
 * every possible reveal type from the remaining {@link BeliefState} pool is searched with a full
 * window and then combined by probability-weighted expectation.
 */
public final class ExpectiAgent implements Agent {

    public static final int DEFAULT_MAX_DEPTH = 3;
    private static final int TIME_CHECK_INTERVAL_NODES = 2_048;

    private static final int WIN_SCORE = EvalWeights.KING_VALUE * 100;
    private static final int INF = WIN_SCORE * 10;

    private final int maxDepth;
    private final PositionEvaluator evaluator;
    private final BeliefState initialBelief;
    private volatile SearchStats lastStats = new SearchStats(0, 0, 0, false);

    public ExpectiAgent() {
        this(DEFAULT_MAX_DEPTH);
    }

    public ExpectiAgent(int maxDepth) {
        this(maxDepth, new PositionEvaluator());
    }

    public ExpectiAgent(int maxDepth, PositionEvaluator evaluator) {
        this(maxDepth, evaluator, BeliefState.initial());
    }

    ExpectiAgent(int maxDepth, PositionEvaluator evaluator, BeliefState initialBelief) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        this.maxDepth = maxDepth;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.initialBelief = Objects.requireNonNull(initialBelief, "initialBelief").copy();
    }

    @Override
    public Optional<Move> selectMove(PlayerView view, TimeBudget budget) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(budget, "budget");

        List<Move> legalMoves = view.legalMoves();
        if (legalMoves.isEmpty()) {
            lastStats = new SearchStats(0, 0, 0, false);
            return Optional.empty();
        }

        BoardSnapshot board = view.informationBoard();
        Color side = view.sideToMove();
        Move bestMove = legalMoves.get(0);

        for (Move move : legalMoves) {
            if (capturesKing(board, move)) {
                lastStats = new SearchStats(0, 0, 0, false);
                return Optional.of(move);
            }
        }

        SearchContext context = new SearchContext(budget);
        if (budget.expired()) {
            context.timedOut = true;
            lastStats = context.toStats(0);
            return Optional.of(bestMove);
        }

        for (int depth = 1; depth <= maxDepth; depth++) {
            try {
                RootResult result = searchRoot(board, side, legalMoves, depth, context);
                bestMove = result.move();
                context.completedDepth = depth;
            } catch (SearchTimeout timeout) {
                context.timedOut = true;
                break;
            }
        }
        lastStats = context.toStats(context.completedDepth);
        return Optional.of(bestMove);
    }

    public SearchStats lastStats() {
        return lastStats;
    }

    int scoreMoveForTesting(PlayerView view, Move move, int depth, BeliefState belief, int alpha, int beta) {
        SearchContext context = new SearchContext(TimeBudget.unlimited());
        return scoreMove(
                view.informationBoard(),
                view.sideToMove(),
                move,
                depth,
                alpha,
                beta,
                belief.copy(),
                context);
    }

    int scoreMoveAsRevealForTesting(
            PlayerView view,
            Move move,
            int depth,
            BeliefState belief,
            PieceType flipAs) {
        SearchContext context = new SearchContext(TimeBudget.unlimited());
        return scoreKnownMove(
                view.informationBoard(),
                view.sideToMove(),
                move,
                depth,
                -INF,
                INF,
                belief.copy(),
                context,
                flipAs);
    }

    private RootResult searchRoot(
            BoardSnapshot board,
            Color side,
            List<Move> legalMoves,
            int depth,
            SearchContext context) {
        Move bestMove = legalMoves.get(0);
        int bestScore = Integer.MIN_VALUE;
        int alpha = -INF;
        BeliefState rootBelief = initialBelief.copy();
        for (Move move : legalMoves) {
            context.checkTime();
            int score = scoreMove(board, side, move, depth, alpha, INF, rootBelief, context);
            if (score > bestScore) {
                bestMove = move;
                bestScore = score;
            }
            alpha = Math.max(alpha, bestScore);
        }
        return new RootResult(bestMove, bestScore);
    }

    private int negamax(
            BoardSnapshot board,
            Color side,
            int depth,
            int alpha,
            int beta,
            BeliefState belief,
            SearchContext context) {
        context.enterNode();
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
            int score = scoreMove(board, side, move, depth, alpha, beta, belief, context);
            if (score > best) {
                best = score;
            }
            alpha = Math.max(alpha, score);
            if (alpha >= beta) {
                context.betaCutoffs++;
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

    private int scoreMove(
            BoardSnapshot board,
            Color side,
            Move move,
            int depth,
            int alpha,
            int beta,
            BeliefState belief,
            SearchContext context) {
        CellState source = board.cellAt(move.from());
        if (source instanceof CellState.Hidden) {
            return scoreHiddenMove(board, side, move, depth, belief, context);
        }
        return scoreKnownMove(board, side, move, depth, alpha, beta, belief, context, null);
    }

    private int scoreHiddenMove(
            BoardSnapshot board,
            Color side,
            Move move,
            int depth,
            BeliefState belief,
            SearchContext context) {
        int poolSize = belief.poolSize(side);
        List<PieceType> availableTypes = belief.availableTypes(side);
        if (poolSize == 0 || availableTypes.isEmpty()) {
            context.checkTime();
            return scoreKnownMove(board, side, move, depth, -INF, INF, belief, context, PieceType.PAWN);
        }

        long weightedScore = 0;
        for (PieceType flipAs : availableTypes) {
            context.checkTime();
            int count = belief.count(side, flipAs);
            int score = scoreKnownMove(board, side, move, depth, -INF, INF, belief, context, flipAs);
            weightedScore += (long) score * count;
        }
        return (int) Math.round((double) weightedScore / poolSize);
    }

    private int scoreKnownMove(
            BoardSnapshot board,
            Color side,
            Move move,
            int depth,
            int alpha,
            int beta,
            BeliefState belief,
            SearchContext context,
            PieceType flipAs) {
        SearchState next = apply(board, side, move, belief.copy(), flipAs);
        return -negamax(next.board(), side.opposite(), depth - 1, -beta, -alpha, next.belief(), context);
    }

    private SearchState apply(BoardSnapshot board, Color side, Move move, BeliefState belief, PieceType flipAs) {
        CellState source = board.cellAt(move.from());
        CellState target = board.cellAt(move.to());
        if (source instanceof CellState.Hidden) {
            if (flipAs == null) {
                flipAs = PieceType.PAWN;
            }
            if (belief.count(side, flipAs) > 0) {
                belief.recordKnownReveal(side, flipAs);
            }
        }
        if (target instanceof CellState.Hidden hiddenTarget) {
            belief.recordUnknownRemoval(hiddenTarget.color());
        }
        return new SearchState(board.apply(move.from(), move.to(), flipAs), belief);
    }

    private boolean capturesKing(BoardSnapshot board, Move move) {
        return board.cellAt(move.to()) instanceof CellState.Revealed revealed
                && revealed.type() == PieceType.KING;
    }

    private record SearchState(BoardSnapshot board, BeliefState belief) {
    }

    private record RootResult(Move move, int score) {
    }

    private static final class SearchContext {
        private final TimeBudget budget;
        private int completedDepth;
        private long searchedNodes;
        private long betaCutoffs;
        private boolean timedOut;

        private SearchContext(TimeBudget budget) {
            this.budget = budget;
        }

        private void enterNode() {
            searchedNodes++;
            if (searchedNodes % TIME_CHECK_INTERVAL_NODES == 0) {
                checkTime();
            }
        }

        private void checkTime() {
            if (budget.expired()) {
                timedOut = true;
                throw SearchTimeout.INSTANCE;
            }
        }

        private SearchStats toStats(int completedDepth) {
            return new SearchStats(completedDepth, searchedNodes, betaCutoffs, timedOut);
        }
    }

    private static final class SearchTimeout extends RuntimeException {
        private static final SearchTimeout INSTANCE = new SearchTimeout();

        private SearchTimeout() {
            super(null, null, false, false);
        }
    }
}
