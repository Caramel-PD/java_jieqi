package jieqi.ai;

import jieqi.common.Move;

import java.util.Optional;

/**
 * Minimal AI contract for selecting a move from the agent's own information view.
 */
public interface Agent {

    /**
     * Selects a move from {@code view}.
     *
     * @return a legal move, or {@link Optional#empty()} when no legal move exists
     */
    Optional<Move> selectMove(PlayerView view, TimeBudget budget);
}
