package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.PieceType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Dual hidden-piece pool model for AI-side imperfect information.
 */
public final class BeliefState {

    private final Map<Color, EnumMap<PieceType, Integer>> pools;
    private final Map<Color, Integer> unknownRemovals;

    private BeliefState(Map<Color, EnumMap<PieceType, Integer>> pools, Map<Color, Integer> unknownRemovals) {
        this.pools = pools;
        this.unknownRemovals = unknownRemovals;
    }

    public static BeliefState initial() {
        Map<Color, EnumMap<PieceType, Integer>> pools = new EnumMap<>(Color.class);
        Map<Color, Integer> unknownRemovals = new EnumMap<>(Color.class);
        for (Color side : Color.values()) {
            pools.put(side, initialPool());
            unknownRemovals.put(side, 0);
        }
        return new BeliefState(pools, unknownRemovals);
    }

    public BeliefState copy() {
        Map<Color, EnumMap<PieceType, Integer>> copiedPools = new EnumMap<>(Color.class);
        Map<Color, Integer> copiedUnknownRemovals = new EnumMap<>(Color.class);
        for (Color side : Color.values()) {
            copiedPools.put(side, new EnumMap<>(pools.get(side)));
            copiedUnknownRemovals.put(side, unknownRemovals(side));
        }
        return new BeliefState(copiedPools, copiedUnknownRemovals);
    }

    public int count(Color side, PieceType type) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        return pools.get(side).getOrDefault(type, 0);
    }

    public double probability(Color side, PieceType type) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        int total = poolSize(side);
        if (total == 0) {
            return 0.0;
        }
        return (double) count(side, type) / total;
    }

    public int poolSize(Color side) {
        Objects.requireNonNull(side, "side");
        return remainingKnownPoolSize(side);
    }

    public List<PieceType> availableTypes(Color side) {
        Objects.requireNonNull(side, "side");
        List<PieceType> types = new ArrayList<>();
        for (PieceType type : PieceType.values()) {
            if (type != PieceType.KING && count(side, type) > 0) {
                types.add(type);
            }
        }
        return List.copyOf(types);
    }

    public int expectedValue(Color side) {
        Objects.requireNonNull(side, "side");
        int total = poolSize(side);
        if (total == 0) {
            return 0;
        }
        int weighted = 0;
        for (Map.Entry<PieceType, Integer> entry : pools.get(side).entrySet()) {
            weighted += entry.getValue() * EvalWeights.pieceValue(entry.getKey());
        }
        return Math.round((float) weighted / total);
    }

    public BeliefState recordKnownReveal(Color side, PieceType type) {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
        if (type == PieceType.KING) {
            throw new IllegalArgumentException("hidden pool never contains king");
        }
        EnumMap<PieceType, Integer> pool = pools.get(side);
        int current = pool.getOrDefault(type, 0);
        if (current <= 0) {
            throw new IllegalStateException("no " + type + " left in " + side + " hidden pool");
        }
        pool.put(type, current - 1);
        return this;
    }

    public BeliefState recordUnknownRemoval(Color side) {
        Objects.requireNonNull(side, "side");
        unknownRemovals.put(side, unknownRemovals(side) + 1);
        return this;
    }

    public int unknownRemovals(Color side) {
        Objects.requireNonNull(side, "side");
        return unknownRemovals.getOrDefault(side, 0);
    }

    private int remainingKnownPoolSize(Color side) {
        return pools.get(side).values().stream().mapToInt(Integer::intValue).sum();
    }

    private static EnumMap<PieceType, Integer> initialPool() {
        EnumMap<PieceType, Integer> pool = new EnumMap<>(PieceType.class);
        pool.put(PieceType.ROOK, 2);
        pool.put(PieceType.KNIGHT, 2);
        pool.put(PieceType.CANNON, 2);
        pool.put(PieceType.PAWN, 5);
        pool.put(PieceType.GUARD, 2);
        pool.put(PieceType.BISHOP, 2);
        return pool;
    }
}
