package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.CellState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded transposition table for ExpectiAgent search.
 */
public final class TranspositionTable {

    public static final int DEFAULT_MAX_CAPACITY = 100_000;

    private static final int BOARD_WORD_COUNT = 8;
    private static final int CELL_BITS = 5;
    private static final int CELLS_PER_WORD = Long.SIZE / CELL_BITS;
    private static final Coord[][] COORDS = createCoords();
    private static final PieceType[] HIDDEN_POOL_TYPES = {
            PieceType.ROOK,
            PieceType.KNIGHT,
            PieceType.CANNON,
            PieceType.PAWN,
            PieceType.GUARD,
            PieceType.BISHOP
    };

    private static final TranspositionTable DISABLED = new TranspositionTable(0);

    private final int maxCapacity;
    private final Map<PositionKey, TranspositionEntry> entries = new HashMap<>();
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

    public static PositionKey positionKey(BoardSnapshot board, Color side, BeliefState belief) {
        Objects.requireNonNull(board, "board");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(belief, "belief");

        long[] boardWords = new long[BOARD_WORD_COUNT];
        int square = 0;
        for (int rank = 0; rank < 10; rank++) {
            for (int file = 0; file < 9; file++) {
                int word = square / CELLS_PER_WORD;
                int offset = (square % CELLS_PER_WORD) * CELL_BITS;
                boardWords[word] |= (long) cellCode(board.cellAt(COORDS[rank][file])) << offset;
                square++;
            }
        }
        return new PositionKey(boardWords, side.code, beliefBits(belief));
    }

    public ProbeResult probe(PositionKey positionKey, int requiredDepth, int alpha, int beta) {
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

    public Optional<TranspositionEntry> entry(PositionKey positionKey) {
        Objects.requireNonNull(positionKey, "positionKey");
        return Optional.ofNullable(entries.get(positionKey));
    }

    public Optional<Move> bestMove(PositionKey positionKey) {
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

    private static int cellCode(CellState cell) {
        if (cell.isEmpty()) {
            return 0;
        }
        if (cell instanceof CellState.Hidden hidden) {
            return hidden.color().code + 1;
        }
        CellState.Revealed revealed = (CellState.Revealed) cell;
        return 3 + revealed.color().code * PieceType.values().length + revealed.type().code;
    }

    private static long beliefBits(BeliefState belief) {
        long bits = 0;
        int shift = 0;
        for (Color color : Color.values()) {
            for (PieceType type : HIDDEN_POOL_TYPES) {
                bits |= (long) belief.count(color, type) << shift;
                shift += 3;
            }
            bits |= (long) belief.unknownRemovals(color) << shift;
            shift += 5;
        }
        return bits;
    }

    private static Coord[][] createCoords() {
        Coord[][] coords = new Coord[10][9];
        for (int rank = 0; rank < 10; rank++) {
            for (int file = 0; file < 9; file++) {
                coords[rank][file] = new Coord(file, rank);
            }
        }
        return coords;
    }

    public static final class PositionKey {
        private final long[] boardWords;
        private final int sideCode;
        private final long beliefBits;
        private final int hash;

        private PositionKey(long[] boardWords, int sideCode, long beliefBits) {
            this.boardWords = boardWords;
            this.sideCode = sideCode;
            this.beliefBits = beliefBits;
            int result = Arrays.hashCode(boardWords);
            result = 31 * result + sideCode;
            result = 31 * result + Long.hashCode(beliefBits);
            this.hash = result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PositionKey other)) {
                return false;
            }
            return sideCode == other.sideCode
                    && beliefBits == other.beliefBits
                    && Arrays.equals(boardWords, other.boardWords);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return "PositionKey{hash=" + hash + ",side=" + sideCode + ",belief=" + beliefBits + '}';
        }
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
