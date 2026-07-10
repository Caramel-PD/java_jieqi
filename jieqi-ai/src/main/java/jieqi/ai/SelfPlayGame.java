package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.BoardText;
import jieqi.rules.CellState;
import jieqi.rules.RuleEngine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * One local self-play game driven by the shared rule engine.
 */
public final class SelfPlayGame {

    public enum Winner {
        RED,
        BLACK,
        DRAW
    }

    public record PlayedGame(Winner winner, int plies, PlayerView redView, PlayerView blackView) {
        public PlayedGame {
            Objects.requireNonNull(winner, "winner");
            if (plies < 0) {
                throw new IllegalArgumentException("plies must be >= 0");
            }
            if (redView != null) {
                Objects.requireNonNull(blackView, "blackView");
            }
            if (blackView != null) {
                Objects.requireNonNull(redView, "redView");
            }
        }

        public PlayedGame(Winner winner, int plies) {
            this(winner, plies, null, null);
        }
    }

    private final Agent redAgent;
    private final Agent blackAgent;
    private final BoardText.ParsedPosition initialPosition;
    private final int maxPlies;
    private final Map<Color, HiddenPool> hiddenPools;

    public SelfPlayGame(Agent redAgent, Agent blackAgent, long seed, int maxPlies) {
        this.redAgent = Objects.requireNonNull(redAgent, "redAgent");
        this.blackAgent = Objects.requireNonNull(blackAgent, "blackAgent");
        this.initialPosition = BoardText.parse(BoardText.INITIAL);
        this.maxPlies = requireNonNegativeMaxPlies(maxPlies);
        this.hiddenPools = createHiddenPools(seed);
    }

    SelfPlayGame(
            Agent redAgent,
            Agent blackAgent,
            BoardText.ParsedPosition initialPosition,
            Map<Color, List<PieceType>> hiddenPieces,
            int maxPlies) {
        this.redAgent = Objects.requireNonNull(redAgent, "redAgent");
        this.blackAgent = Objects.requireNonNull(blackAgent, "blackAgent");
        this.initialPosition = Objects.requireNonNull(initialPosition, "initialPosition");
        this.maxPlies = requireNonNegativeMaxPlies(maxPlies);
        this.hiddenPools = createHiddenPools(Objects.requireNonNull(hiddenPieces, "hiddenPieces"));
    }

    public PlayedGame play() {
        PlayerView redView = PlayerView.of(initialPosition.board(), initialPosition.sideToMove());
        PlayerView blackView = PlayerView.of(initialPosition.board(), initialPosition.sideToMove());
        int plies = 0;

        while (plies < maxPlies) {
            PlayerView currentView = viewFor(redView, blackView, redView.sideToMove());
            BoardSnapshot board = currentView.informationBoard();
            Color sideToMove = currentView.sideToMove();
            List<Move> legalMoves = RuleEngine.generateLegalMoves(board, sideToMove);
            if (legalMoves.isEmpty()) {
                return new PlayedGame(Winner.DRAW, plies, redView, blackView);
            }

            Move move = selectMove(agentFor(sideToMove), currentView, legalMoves);
            CellState source = board.cellAt(move.from());
            CellState target = board.cellAt(move.to());
            Optional<Color> capturedKing = capturedKingColor(target);
            PieceType flipAs = source instanceof CellState.Hidden
                    ? hiddenPools.get(sideToMove).draw()
                    : null;
            PieceType capturedHiddenAs = target instanceof CellState.Hidden hiddenTarget
                    ? hiddenPools.get(hiddenTarget.color()).draw()
                    : null;

            MoveResultMessage captorResult = new MoveResultMessage(
                    true,
                    move,
                    flipAs != null,
                    Optional.ofNullable(flipAs),
                    capturedPieceForCaptor(target, capturedHiddenAs));
            MoveResultMessage victimResult = new MoveResultMessage(
                    true,
                    move,
                    flipAs != null,
                    Optional.ofNullable(flipAs),
                    capturedPieceForVictim(target));
            if (sideToMove == Color.RED) {
                redView = redView.apply(captorResult);
                blackView = blackView.apply(victimResult);
            } else {
                redView = redView.apply(victimResult);
                blackView = blackView.apply(captorResult);
            }
            plies++;

            if (capturedKing.isPresent()
                    || RuleEngine.isKingCaptured(redView.informationBoard(), sideToMove.opposite())) {
                return new PlayedGame(winnerFor(sideToMove), plies, redView, blackView);
            }
        }

        return new PlayedGame(Winner.DRAW, plies, redView, blackView);
    }

    private Move selectMove(Agent agent, PlayerView view, List<Move> legalMoves) {
        Optional<Move> selected = agent.selectMove(view, TimeBudget.unlimited());
        if (selected.isEmpty()) {
            throw new IllegalStateException("agent returned no move while legal moves exist");
        }
        Move move = selected.get();
        if (!legalMoves.contains(move)) {
            throw new IllegalStateException("agent selected illegal move: " + move);
        }
        return move;
    }

    private Agent agentFor(Color side) {
        return side == Color.RED ? redAgent : blackAgent;
    }

    private static PlayerView viewFor(PlayerView redView, PlayerView blackView, Color side) {
        return side == Color.RED ? redView : blackView;
    }

    private static Optional<Color> capturedKingColor(CellState target) {
        if (target instanceof CellState.Revealed revealed && revealed.type() == PieceType.KING) {
            return Optional.of(revealed.color());
        }
        return Optional.empty();
    }

    private static MoveResultMessage.CapturedPiece capturedPieceForCaptor(CellState target, PieceType capturedHiddenAs) {
        if (target instanceof CellState.Hidden) {
            return MoveResultMessage.CapturedPiece.known(Objects.requireNonNull(capturedHiddenAs, "capturedHiddenAs"));
        }
        if (target instanceof CellState.Revealed revealed) {
            return MoveResultMessage.CapturedPiece.known(revealed.type());
        }
        return MoveResultMessage.CapturedPiece.none();
    }

    private static MoveResultMessage.CapturedPiece capturedPieceForVictim(CellState target) {
        if (target instanceof CellState.Hidden) {
            return MoveResultMessage.CapturedPiece.unknown();
        }
        return capturedPieceForCaptor(target, null);
    }

    private static Winner winnerFor(Color side) {
        return side == Color.RED ? Winner.RED : Winner.BLACK;
    }

    private static int requireNonNegativeMaxPlies(int maxPlies) {
        if (maxPlies < 0) {
            throw new IllegalArgumentException("maxPlies must be >= 0");
        }
        return maxPlies;
    }

    private static Map<Color, HiddenPool> createHiddenPools(long seed) {
        Random random = new Random(seed);
        Map<Color, HiddenPool> pools = new EnumMap<>(Color.class);
        pools.put(Color.RED, new HiddenPool(random.nextLong()));
        pools.put(Color.BLACK, new HiddenPool(random.nextLong()));
        return pools;
    }

    private static Map<Color, HiddenPool> createHiddenPools(Map<Color, List<PieceType>> hiddenPieces) {
        Map<Color, HiddenPool> pools = new EnumMap<>(Color.class);
        for (Color side : Color.values()) {
            pools.put(side, new HiddenPool(hiddenPieces.getOrDefault(side, defaultHiddenPieces())));
        }
        return pools;
    }

    static Map<Color, List<PieceType>> hiddenPoolsForTesting(PieceType firstBlackDraw) {
        Map<Color, List<PieceType>> pools = new EnumMap<>(Color.class);
        pools.put(Color.RED, defaultHiddenPieces());
        List<PieceType> black = new ArrayList<>(defaultHiddenPieces());
        black.remove(Objects.requireNonNull(firstBlackDraw, "firstBlackDraw"));
        black.add(firstBlackDraw);
        pools.put(Color.BLACK, List.copyOf(black));
        return pools;
    }

    int remainingHiddenPiecesForTesting(Color side) {
        return hiddenPools.get(Objects.requireNonNull(side, "side")).remaining();
    }

    private static List<PieceType> defaultHiddenPieces() {
        List<PieceType> pieces = new ArrayList<>(15);
        add(pieces, PieceType.ROOK, 2);
        add(pieces, PieceType.KNIGHT, 2);
        add(pieces, PieceType.BISHOP, 2);
        add(pieces, PieceType.GUARD, 2);
        add(pieces, PieceType.CANNON, 2);
        add(pieces, PieceType.PAWN, 5);
        return List.copyOf(pieces);
    }

    private static void add(List<PieceType> pieces, PieceType type, int count) {
        for (int i = 0; i < count; i++) {
            pieces.add(type);
        }
    }

    private static final class HiddenPool {
        private final List<PieceType> pieces = new ArrayList<>(15);

        private HiddenPool(long seed) {
            pieces.addAll(defaultHiddenPieces());
            Collections.shuffle(pieces, new Random(seed));
        }

        private HiddenPool(List<PieceType> pieces) {
            this.pieces.addAll(pieces);
        }

        PieceType draw() {
            if (pieces.isEmpty()) {
                throw new IllegalStateException("hidden pool exhausted");
            }
            return pieces.remove(pieces.size() - 1);
        }

        private int remaining() {
            return pieces.size();
        }
    }
}
