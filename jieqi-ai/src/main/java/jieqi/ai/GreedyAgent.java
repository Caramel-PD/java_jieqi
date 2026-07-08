package jieqi.ai;

import jieqi.common.Move;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * L1 baseline agent: prefer higher-value captures, otherwise play the first legal move.
 */
public final class GreedyAgent implements Agent {

    private final PositionEvaluator evaluator;

    public GreedyAgent() {
        this(new PositionEvaluator());
    }

    public GreedyAgent(PositionEvaluator evaluator) {
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
        Move bestCapture = null;
        int bestCaptureValue = 0;
        for (Move move : legalMoves) {
            int captureValue = evaluator.captureValue(view, move);
            if (captureValue > bestCaptureValue) {
                bestCapture = move;
                bestCaptureValue = captureValue;
            }
        }
        return Optional.of(bestCapture != null ? bestCapture : legalMoves.get(0));
    }
}
