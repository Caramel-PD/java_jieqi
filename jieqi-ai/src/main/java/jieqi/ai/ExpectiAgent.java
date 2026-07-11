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
    private static final int MAX_QUIESCENCE_DEPTH = 4;
    private static final int TIME_CHECK_INTERVAL_NODES = 2_048;

    private static final int WIN_SCORE = EvalWeights.KING_VALUE * 100;
    private static final int INF = WIN_SCORE * 10;

    private final int maxDepth;
    private final PositionEvaluator evaluator;
    private final MoveOrderer moveOrderer;
    private final TranspositionTable transpositionTable;
    private volatile SearchStats lastStats = new SearchStats(0, 0, 0, 0, 0, 0, 0, false);

    public ExpectiAgent() {
        this(DEFAULT_MAX_DEPTH);
    }

    public ExpectiAgent(int maxDepth) {
        this(maxDepth, new PositionEvaluator());
    }

    public ExpectiAgent(int maxDepth, PositionEvaluator evaluator) {
        this(maxDepth, evaluator, new MoveOrderer());
    }

    ExpectiAgent(int maxDepth, PositionEvaluator evaluator, MoveOrderer moveOrderer) {
        this(maxDepth, evaluator, moveOrderer, new TranspositionTable());
    }

    ExpectiAgent(
            int maxDepth,
            PositionEvaluator evaluator,
            MoveOrderer moveOrderer,
            TranspositionTable transpositionTable) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maxDepth must be >= 1");
        }
        this.maxDepth = maxDepth;
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
        this.moveOrderer = Objects.requireNonNull(moveOrderer, "moveOrderer");
        this.transpositionTable = Objects.requireNonNull(transpositionTable, "transpositionTable");
    }

    @Override
    public Optional<Move> selectMove(PlayerView view, TimeBudget budget) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(budget, "budget");

        List<Move> legalMoves = view.legalMoves();
        if (legalMoves.isEmpty()) {
            lastStats = new SearchStats(0, 0, 0, 0, 0, 0, 0, false);
            return Optional.empty();
        }

        BoardSnapshot board = view.informationBoard();
        Color side = view.sideToMove();
        BeliefState rootBelief = view.beliefState();
        List<Move> orderedLegalMoves = moveOrderer.order(board, side, legalMoves, rootBelief);
        Move bestMove = orderedLegalMoves.get(0);

        for (Move move : orderedLegalMoves) {
            if (capturesKing(board, move)) {
                lastStats = new SearchStats(0, 0, 0, 0, 0, 0, 0, false);
                return Optional.of(move);
            }
        }

        SearchContext context = new SearchContext(budget, transpositionTable);
        if (budget.expired()) {
            context.timedOut = true;
            lastStats = context.toStats(0);
            return Optional.of(bestMove);
        }

        for (int depth = 1; depth <= maxDepth; depth++) {
            try {
                RootResult result = searchRoot(board, side, orderedLegalMoves, rootBelief, depth, context);
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

    int scoreMoveForTesting(PlayerView view, Move move, int depth, int alpha, int beta) {
        SearchContext context = new SearchContext(TimeBudget.unlimited(), TranspositionTable.disabled());
        return scoreMove(
                view.informationBoard(),
                view.sideToMove(),
                move,
                depth,
                alpha,
                beta,
                view.beliefState(),
                context);
    }

    int scoreMoveForTesting(PlayerView view, Move move, int depth, BeliefState belief, int alpha, int beta) {
        SearchContext context = new SearchContext(TimeBudget.unlimited(), TranspositionTable.disabled());
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

    int scoreMoveAsRevealForTesting(PlayerView view, Move move, int depth, BeliefState belief, PieceType flipAs) {
        SearchContext context = new SearchContext(TimeBudget.unlimited(), TranspositionTable.disabled());
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

    int staticEvaluateForTesting(PlayerView view) {
        return evaluate(view.informationBoard(), view.sideToMove(), view.beliefState());
    }

    int quiescenceScoreForTesting(PlayerView view) {
        SearchContext context = new SearchContext(TimeBudget.unlimited(), TranspositionTable.disabled());
        int score = quiescence(view.informationBoard(), view.sideToMove(), -INF, INF, view.beliefState(), context, 0);
        lastStats = context.toStats(0);
        return score;
    }

    int scoreQuiescenceMoveForTesting(PlayerView view, Move move, BeliefState belief) {
        SearchContext context = new SearchContext(TimeBudget.unlimited(), TranspositionTable.disabled());
        int score = scoreQuiescenceMove(
                view.informationBoard(),
                view.sideToMove(),
                move,
                -INF,
                INF,
                belief.copy(),
                context,
                0);
        lastStats = context.toStats(0);
        return score;
    }

    int scoreQuiescenceMoveAsRevealForTesting(
            PlayerView view,
            Move move,
            BeliefState belief,
            PieceType flipAs) {
        SearchContext context = new SearchContext(TimeBudget.unlimited(), TranspositionTable.disabled());
        int score = scoreKnownQuiescenceMove(
                view.informationBoard(),
                view.sideToMove(),
                move,
                -INF,
                INF,
                belief.copy(),
                context,
                0,
                flipAs);
        lastStats = context.toStats(0);
        return score;
    }

    private RootResult searchRoot(
            BoardSnapshot board,
            Color side,
            List<Move> legalMoves,
            BeliefState belief,
            int depth,
            SearchContext context) {
        String positionKey = TranspositionTable.positionKey(board, side, belief);
        int alpha = -INF;
        int beta = INF;
        int originalAlpha = alpha;
        int originalBeta = beta;
        TranspositionTable.ProbeResult probe = context.probe(positionKey, depth, alpha, beta);
        alpha = probe.alpha();
        beta = probe.beta();
        List<Move> orderedMoves = moveOrderer.order(
                board,
                side,
                legalMoves,
                belief,
                probe.bestMove().orElse(context.bestMove(positionKey).orElse(null)));
        Move bestMove = orderedMoves.get(0);
        if (probe.cutoff()) {
            return new RootResult(probe.bestMove().orElse(bestMove), probe.score());
        }
        int bestScore = Integer.MIN_VALUE;
        BeliefState rootBelief = belief.copy();
        for (Move move : orderedMoves) {
            context.checkTime();
            int score = scoreMove(board, side, move, depth, alpha, beta, rootBelief, context);
            if (score > bestScore) {
                bestMove = move;
                bestScore = score;
            }
            alpha = Math.max(alpha, bestScore);
        }
        storeBound(context, positionKey, depth, bestScore, originalAlpha, originalBeta, bestMove);
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
        String positionKey = TranspositionTable.positionKey(board, side, belief);
        int originalAlpha = alpha;
        int originalBeta = beta;
        TranspositionTable.ProbeResult probe = context.probe(positionKey, depth, alpha, beta);
        alpha = probe.alpha();
        beta = probe.beta();
        if (probe.cutoff()) {
            return probe.score();
        }
        context.enterNode();
        int result;
        if (RuleEngine.isKingCaptured(board, side)) {
            result = -WIN_SCORE - depth;
            context.store(new TranspositionEntry(positionKey, depth, result, BoundType.EXACT, null));
            return result;
        }
        if (RuleEngine.isKingCaptured(board, side.opposite())) {
            result = WIN_SCORE + depth;
            context.store(new TranspositionEntry(positionKey, depth, result, BoundType.EXACT, null));
            return result;
        }
        if (depth <= 0) {
            result = quiescence(board, side, alpha, beta, belief, context, 0);
            storeBound(context, positionKey, depth, result, originalAlpha, originalBeta, null);
            return result;
        }

        List<Move> legalMoves = moveOrderer.order(
                board,
                side,
                RuleEngine.generateLegalMoves(board, side),
                belief,
                context.bestMove(positionKey).orElse(null));
        if (legalMoves.isEmpty()) {
            result = evaluate(board, side, belief);
            context.store(new TranspositionEntry(positionKey, depth, result, BoundType.EXACT, null));
            return result;
        }

        int best = -INF;
        Move bestMove = legalMoves.get(0);
        for (Move move : legalMoves) {
            if (capturesKing(board, move)) {
                result = WIN_SCORE + depth;
                context.store(new TranspositionEntry(positionKey, depth, result, BoundType.EXACT, move));
                return result;
            }
            int score = scoreMove(board, side, move, depth, alpha, beta, belief, context);
            if (score > best) {
                best = score;
                bestMove = move;
            }
            alpha = Math.max(alpha, score);
            if (alpha >= beta) {
                context.betaCutoffs++;
                break;
            }
        }
        storeBound(context, positionKey, depth, best, originalAlpha, originalBeta, bestMove);
        return best;
    }

    private int quiescence(
            BoardSnapshot board,
            Color side,
            int alpha,
            int beta,
            BeliefState belief,
            SearchContext context,
            int quiescenceDepth) {
        context.enterQuiescenceNode();
        if (RuleEngine.isKingCaptured(board, side)) {
            return -WIN_SCORE - quiescenceDepth;
        }
        if (RuleEngine.isKingCaptured(board, side.opposite())) {
            return WIN_SCORE + quiescenceDepth;
        }

        boolean inImmediateKingThreat = hasImmediateKingThreat(board, side);
        int standPat = evaluate(board, side, belief);
        if (quiescenceDepth >= MAX_QUIESCENCE_DEPTH) {
            return standPat;
        }
        if (!inImmediateKingThreat) {
            if (standPat >= beta) {
                return standPat;
            }
            alpha = Math.max(alpha, standPat);
        }

        List<Move> legalMoves = moveOrderer.order(
                board,
                side,
                RuleEngine.generateLegalMoves(board, side),
                belief);
        int best = inImmediateKingThreat ? -INF : standPat;
        boolean searched = false;
        for (Move move : legalMoves) {
            context.checkTime();
            if (!isQuiescenceMove(board, side, move, belief, inImmediateKingThreat)) {
                continue;
            }
            searched = true;
            int score = scoreQuiescenceMove(board, side, move, alpha, beta, belief, context, quiescenceDepth);
            if (score > best) {
                best = score;
            }
            alpha = Math.max(alpha, score);
            if (alpha >= beta) {
                context.betaCutoffs++;
                break;
            }
        }
        if (inImmediateKingThreat && !searched) {
            best = -WIN_SCORE + quiescenceDepth;
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

    private int scoreQuiescenceMove(
            BoardSnapshot board,
            Color side,
            Move move,
            int alpha,
            int beta,
            BeliefState belief,
            SearchContext context,
            int quiescenceDepth) {
        CellState source = board.cellAt(move.from());
        if (source instanceof CellState.Hidden) {
            return scoreHiddenQuiescenceMove(board, side, move, belief, context, quiescenceDepth);
        }
        return scoreKnownQuiescenceMove(board, side, move, alpha, beta, belief, context, quiescenceDepth, null);
    }

    private int scoreHiddenQuiescenceMove(
            BoardSnapshot board,
            Color side,
            Move move,
            BeliefState belief,
            SearchContext context,
            int quiescenceDepth) {
        int poolSize = belief.poolSize(side);
        List<PieceType> availableTypes = belief.availableTypes(side);
        if (poolSize == 0 || availableTypes.isEmpty()) {
            context.checkTime();
            return scoreKnownQuiescenceMove(board, side, move, -INF, INF, belief, context, quiescenceDepth, PieceType.PAWN);
        }

        long weightedScore = 0;
        for (PieceType flipAs : availableTypes) {
            context.checkTime();
            int count = belief.count(side, flipAs);
            int score = scoreKnownQuiescenceMove(
                    board,
                    side,
                    move,
                    -INF,
                    INF,
                    belief,
                    context,
                    quiescenceDepth,
                    flipAs);
            weightedScore += (long) score * count;
        }
        return (int) Math.round((double) weightedScore / poolSize);
    }

    private int scoreKnownQuiescenceMove(
            BoardSnapshot board,
            Color side,
            Move move,
            int alpha,
            int beta,
            BeliefState belief,
            SearchContext context,
            int quiescenceDepth,
            PieceType flipAs) {
        SearchState next = apply(board, side, move, belief.copy(), flipAs);
        return -quiescence(
                next.board(),
                side.opposite(),
                -beta,
                -alpha,
                next.belief(),
                context,
                quiescenceDepth + 1);
    }

    private boolean isQuiescenceMove(
            BoardSnapshot board,
            Color side,
            Move move,
            BeliefState belief,
            boolean inImmediateKingThreat) {
        if (capturesKing(board, move)) {
            return true;
        }
        if (isCapture(board, move)) {
            return true;
        }
        return inImmediateKingThreat && resolvesImmediateKingThreat(board, side, move, belief);
    }

    private boolean resolvesImmediateKingThreat(BoardSnapshot board, Color side, Move move, BeliefState belief) {
        CellState source = board.cellAt(move.from());
        PieceType flipAs = source instanceof CellState.Hidden ? firstAvailableType(belief, side) : null;
        try {
            return !hasImmediateKingThreat(board.apply(move.from(), move.to(), flipAs), side);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private PieceType firstAvailableType(BeliefState belief, Color side) {
        List<PieceType> types = belief.availableTypes(side);
        return types.isEmpty() ? PieceType.PAWN : types.get(0);
    }

    private boolean hasImmediateKingThreat(BoardSnapshot board, Color side) {
        if (RuleEngine.isKingCaptured(board, side)) {
            return true;
        }
        for (Move move : RuleEngine.generateLegalMoves(board, side.opposite())) {
            if (capturesKing(board, move)) {
                return true;
            }
        }
        return false;
    }

    private boolean isCapture(BoardSnapshot board, Move move) {
        return !board.cellAt(move.to()).isEmpty();
    }

    private boolean capturesKing(BoardSnapshot board, Move move) {
        return board.cellAt(move.to()) instanceof CellState.Revealed revealed
                && revealed.type() == PieceType.KING;
    }

    private void storeBound(
            SearchContext context,
            String positionKey,
            int depth,
            int score,
            int originalAlpha,
            int originalBeta,
            Move bestMove) {
        BoundType boundType;
        if (score <= originalAlpha) {
            boundType = BoundType.UPPER;
        } else if (score >= originalBeta) {
            boundType = BoundType.LOWER;
        } else {
            boundType = BoundType.EXACT;
        }
        context.store(new TranspositionEntry(positionKey, depth, score, boundType, bestMove));
    }

    private record SearchState(BoardSnapshot board, BeliefState belief) {
    }

    private record RootResult(Move move, int score) {
    }

    private static final class SearchContext {
        private final TimeBudget budget;
        private final TranspositionTable transpositionTable;
        private int completedDepth;
        private long searchedNodes;
        private long betaCutoffs;
        private long quiescenceNodes;
        private long ttHits;
        private long ttStores;
        private long ttCutoffs;
        private boolean timedOut;

        private SearchContext(TimeBudget budget, TranspositionTable transpositionTable) {
            this.budget = budget;
            this.transpositionTable = transpositionTable;
        }

        private void enterNode() {
            searchedNodes++;
            if (searchedNodes % TIME_CHECK_INTERVAL_NODES == 0) {
                checkTime();
            }
        }

        private void enterQuiescenceNode() {
            quiescenceNodes++;
            checkTime();
        }

        private void checkTime() {
            if (budget.expired()) {
                timedOut = true;
                throw SearchTimeout.INSTANCE;
            }
        }

        private TranspositionTable.ProbeResult probe(String positionKey, int depth, int alpha, int beta) {
            TranspositionTable.ProbeResult result = transpositionTable.probe(positionKey, depth, alpha, beta);
            if (result.hit()) {
                ttHits++;
                if (result.cutoff()) {
                    ttCutoffs++;
                }
            }
            return result;
        }

        private Optional<Move> bestMove(String positionKey) {
            return transpositionTable.bestMove(positionKey);
        }

        private void store(TranspositionEntry entry) {
            if (!timedOut && transpositionTable.store(entry)) {
                ttStores++;
            }
        }

        private SearchStats toStats(int completedDepth) {
            return new SearchStats(
                    completedDepth,
                    searchedNodes,
                    betaCutoffs,
                    quiescenceNodes,
                    ttHits,
                    ttStores,
                    ttCutoffs,
                    timedOut);
        }
    }

    private static final class SearchTimeout extends RuntimeException {
        private static final SearchTimeout INSTANCE = new SearchTimeout();

        private SearchTimeout() {
            super(null, null, false, false);
        }
    }
}
