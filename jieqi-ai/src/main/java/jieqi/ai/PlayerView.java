package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.CellState;
import jieqi.rules.RuleEngine;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The board state visible to one AI player.
 *
 * <p>This is the AI-side state boundary: agents receive a {@code PlayerView}, not a server-side board.
 * The current C-01 implementation stores the information-set board needed to call the shared rule engine;
 * future protocol updates should update this class rather than passing raw board state into agents.
 */
public final class PlayerView {

    private final BoardSnapshot informationBoard;
    private final Color sideToMove;

    private PlayerView(BoardSnapshot informationBoard, Color sideToMove) {
        this.informationBoard = Objects.requireNonNull(informationBoard, "informationBoard");
        this.sideToMove = Objects.requireNonNull(sideToMove, "sideToMove");
    }

    public static PlayerView of(BoardSnapshot informationBoard, Color sideToMove) {
        return new PlayerView(informationBoard, sideToMove);
    }

    public Color sideToMove() {
        return sideToMove;
    }

    public List<Move> legalMoves() {
        return RuleEngine.generateLegalMoves(informationBoard, sideToMove);
    }

    public boolean isOccupied(Coord coord) {
        return !informationBoard.cellAt(Objects.requireNonNull(coord, "coord")).isEmpty();
    }

    public boolean isHidden(Coord coord) {
        return informationBoard.cellAt(Objects.requireNonNull(coord, "coord")) instanceof CellState.Hidden;
    }

    public Optional<PieceType> revealedPieceTypeAt(Coord coord) {
        CellState cell = informationBoard.cellAt(Objects.requireNonNull(coord, "coord"));
        if (cell instanceof CellState.Revealed revealed) {
            return Optional.of(revealed.type());
        }
        return Optional.empty();
    }
}
