package jieqi.ai;

import jieqi.common.Move;

import java.util.Objects;
import java.util.Optional;

/**
 * One cached alpha-beta search result.
 */
public record TranspositionEntry(
        TranspositionTable.PositionKey positionKey,
        int depth,
        int score,
        BoundType boundType,
        Move bestMove) {

    public TranspositionEntry {
        Objects.requireNonNull(positionKey, "positionKey");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0");
        }
        Objects.requireNonNull(boundType, "boundType");
    }

    public Optional<Move> bestMoveOptional() {
        return Optional.ofNullable(bestMove);
    }
}
