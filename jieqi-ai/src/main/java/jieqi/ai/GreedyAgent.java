package jieqi.ai;

import jieqi.common.Move;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * L1 baseline agent: prefer captures, otherwise play the first legal move.
 */
public final class GreedyAgent implements Agent {

    @Override
    public Optional<Move> selectMove(PlayerView view, TimeBudget budget) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(budget, "budget");

        List<Move> legalMoves = view.legalMoves();
        if (legalMoves.isEmpty()) {
            return Optional.empty();
        }
        return legalMoves.stream()
                .filter(move -> view.isOccupied(move.to()))
                .findFirst()
                .or(() -> Optional.of(legalMoves.get(0)));
    }
}
