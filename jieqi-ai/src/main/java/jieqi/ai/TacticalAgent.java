package jieqi.ai;

import jieqi.common.Move;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * L2 baseline agent: score every legal move with capture, flip, and mobility terms.
 */
public final class TacticalAgent implements Agent {

    private final PositionEvaluator evaluator;

    public TacticalAgent() {
        this(new PositionEvaluator());
    }

    public TacticalAgent(PositionEvaluator evaluator) {
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

        Move bestMove = legalMoves.get(0);
        int bestScore = evaluator.evaluateAfterMove(view, bestMove);
        for (int i = 1; i < legalMoves.size(); i++) {
            Move move = legalMoves.get(i);
            int score = evaluator.evaluateAfterMove(view, move);
            if (score > bestScore) {
                bestMove = move;
                bestScore = score;
            }
        }
        return Optional.of(bestMove);
    }
}
