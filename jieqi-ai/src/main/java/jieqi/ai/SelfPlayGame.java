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

    public record PlayedGame(Winner winner, int plies) {
        public PlayedGame {
            Objects.requireNonNull(winner, "winner");
            if (plies < 0) {
                throw new IllegalArgumentException("plies must be >= 0");
            }
        }
    }

    private final Agent redAgent;
    private final Agent blackAgent;
    private final int maxPlies;
    private final Map<Color, HiddenPool> hiddenPools;

    public SelfPlayGame(Agent redAgent, Agent blackAgent, long seed, int maxPlies) {
        this.redAgent = Objects.requireNonNull(redAgent, "redAgent");
        this.blackAgent = Objects.requireNonNull(blackAgent, "blackAgent");
        if (maxPlies < 0) {
            throw new IllegalArgumentException("maxPlies must be >= 0");
        }
        this.maxPlies = maxPlies;
        this.hiddenPools = createHiddenPools(seed);
    }

    public PlayedGame play() {
        BoardText.ParsedPosition initial = BoardText.parse(BoardText.INITIAL);
        PlayerView view = PlayerView.of(initial.board(), initial.sideToMove());
        int plies = 0;

        while (plies < maxPlies) {
            BoardSnapshot board = view.informationBoard();
            Color sideToMove = view.sideToMove();
            List<Move> legalMoves = RuleEngine.generateLegalMoves(board, sideToMove);
            if (legalMoves.isEmpty()) {
                return new PlayedGame(Winner.DRAW, plies);
            }

            Move move = selectMove(agentFor(sideToMove), view, legalMoves);
            CellState source = board.cellAt(move.from());
            CellState target = board.cellAt(move.to());
            Optional<Color> capturedKing = capturedKingColor(target);
            PieceType flipAs = source instanceof CellState.Hidden
                    ? hiddenPools.get(sideToMove).draw()
                    : null;

            view = view.apply(new MoveResultMessage(
                    true,
                    move,
                    flipAs != null,
                    Optional.ofNullable(flipAs),
                    capturedPiece(target)));
            plies++;

            if (capturedKing.isPresent()
                    || RuleEngine.isKingCaptured(view.informationBoard(), sideToMove.opposite())) {
                return new PlayedGame(winnerFor(sideToMove), plies);
            }
        }

        return new PlayedGame(Winner.DRAW, plies);
    }

    private Move selectMove(Agent agent, PlayerView view, List<Move> legalMoves) {
        Optional<Move> selected = agent.selectMove(view, TimeBudget.ofMillis(0));
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

    private static Optional<Color> capturedKingColor(CellState target) {
        if (target instanceof CellState.Revealed revealed && revealed.type() == PieceType.KING) {
            return Optional.of(revealed.color());
        }
        return Optional.empty();
    }

    private static MoveResultMessage.CapturedPiece capturedPiece(CellState target) {
        if (target instanceof CellState.Hidden) {
            return MoveResultMessage.CapturedPiece.unknown();
        }
        if (target instanceof CellState.Revealed revealed) {
            return MoveResultMessage.CapturedPiece.known(revealed.type());
        }
        return MoveResultMessage.CapturedPiece.none();
    }

    private static Winner winnerFor(Color side) {
        return side == Color.RED ? Winner.RED : Winner.BLACK;
    }

    private static Map<Color, HiddenPool> createHiddenPools(long seed) {
        Random random = new Random(seed);
        Map<Color, HiddenPool> pools = new EnumMap<>(Color.class);
        pools.put(Color.RED, new HiddenPool(random.nextLong()));
        pools.put(Color.BLACK, new HiddenPool(random.nextLong()));
        return pools;
    }

    private static final class HiddenPool {
        private final List<PieceType> pieces = new ArrayList<>(15);

        private HiddenPool(long seed) {
            add(PieceType.ROOK, 2);
            add(PieceType.KNIGHT, 2);
            add(PieceType.BISHOP, 2);
            add(PieceType.GUARD, 2);
            add(PieceType.CANNON, 2);
            add(PieceType.PAWN, 5);
            Collections.shuffle(pieces, new Random(seed));
        }

        PieceType draw() {
            if (pieces.isEmpty()) {
                throw new IllegalStateException("hidden pool exhausted");
            }
            return pieces.remove(pieces.size() - 1);
        }

        private void add(PieceType type, int count) {
            for (int i = 0; i < count; i++) {
                pieces.add(type);
            }
        }
    }
}
