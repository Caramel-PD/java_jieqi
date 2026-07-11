package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.BoardText;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded transposition table for ExpectiAgent search.
 */
public final class TranspositionTable {

    public static final int DEFAULT_MAX_CAPACITY = 100_000;

    private static final TranspositionTable DISABLED = new TranspositionTable(0);

    private final int maxCapacity;
    private final Map<String, TranspositionEntry> entries = new HashMap<>();
    private int generation;

    public TranspositionTable() {
        this(DEFAULT_MAX_CAPACITY);
    }

    public TranspositionTable(int maxCapacity) {
        if (maxCapacity < 0) {
            throw new IllegalArgumentException("maxCapacity must be >= 0");
        }
        this.maxCapacity = maxCapacity;
    }

    public static TranspositionTable disabled() {
        return DISABLED;
    }

    public static String positionKey(BoardSnapshot board, Color side, BeliefState belief) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(belief, "belief");

        StringBuilder key = new StringBuilder(BoardText.format(board, side))
                .append("|side=").append(side)
                .append("|belief=");
        for (Color color : Color.values()) {
            key.append(color).append(':');
            for (PieceType type : PieceType.values()) {
                if (type != PieceType.KING) {
                    key.append(type).append('=').append(belief.count(color, type)).append(';');
                }
            }
            key.append("u=").append(belief.unknownRemovals(color)).append('|');
        }
        return key.toString();
    }

    public ProbeResult probe(String positionKey, int requiredDepth, int alpha, int beta) {
        Objects.requireNonNull(positionKey, "positionKey");
        if (requiredDepth < 0) {
            throw new IllegalArgumentException("requiredDepth must be >= 0");
        }
        TranspositionEntry entry = entries.get(positionKey);
        if (entry == null || entry.depth() < requiredDepth) {
            return ProbeResult.miss(alpha, beta);
        }
        if (entry.boundType() == BoundType.EXACT) {
            return ProbeResult.hit(true, entry.score(), alpha, beta, entry);
        }

        int adjustedAlpha = alpha;
        int adjustedBeta = beta;
        if (entry.boundType() == BoundType.LOWER) {
            adjustedAlpha = Math.max(alpha, entry.score());
        } else if (entry.boundType() == BoundType.UPPER) {
            adjustedBeta = Math.min(beta, entry.score());
        }

        boolean cutoff = adjustedAlpha >= adjustedBeta;
        return ProbeResult.hit(cutoff, entry.score(), adjustedAlpha, adjustedBeta, entry);
    }

    public Optional<TranspositionEntry> entry(String positionKey) {
        Objects.requireNonNull(positionKey, "positionKey");
        return Optional.ofNullable(entries.get(positionKey));
    }

    public Optional<Move> bestMove(String positionKey) {
        return entry(positionKey).flatMap(TranspositionEntry::bestMoveOptional);
    }

    public boolean store(TranspositionEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (maxCapacity == 0) {
            return false;
        }
        TranspositionEntry existing = entries.get(entry.positionKey());
        if (existing != null && existing.depth() > entry.depth()) {
            return false;
        }
        if (existing != null
                && existing.depth() == entry.depth()
                && existing.boundType() == BoundType.EXACT
                && entry.boundType() != BoundType.EXACT) {
            return false;
        }
        if (existing == null && entries.size() >= maxCapacity) {
            entries.clear();
            generation++;
        }
        entries.put(entry.positionKey(), entry);
        return true;
    }

    public int size() {
        return entries.size();
    }

    public int generation() {
        return generation;
    }

    public record ProbeResult(
            boolean hit,
            boolean cutoff,
            int score,
            int alpha,
            int beta,
            TranspositionEntry entry) {

        private static ProbeResult miss(int alpha, int beta) {
            return new ProbeResult(false, false, 0, alpha, beta, null);
        }

        private static ProbeResult hit(
                boolean cutoff,
                int score,
                int alpha,
                int beta,
                TranspositionEntry entry) {
            return new ProbeResult(true, cutoff, score, alpha, beta, Objects.requireNonNull(entry, "entry"));
        }

        public Optional<Move> bestMove() {
            return entry == null ? Optional.empty() : entry.bestMoveOptional();
        }
    }
}
