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
    private final BeliefState beliefState;

    private PlayerView(BoardSnapshot informationBoard, Color sideToMove, BeliefState beliefState) {
        this.informationBoard = Objects.requireNonNull(informationBoard, "informationBoard");
        this.sideToMove = Objects.requireNonNull(sideToMove, "sideToMove");
        this.beliefState = Objects.requireNonNull(beliefState, "beliefState").copy();
    }

    public static PlayerView of(BoardSnapshot informationBoard, Color sideToMove) {
        return new PlayerView(informationBoard, sideToMove, BeliefState.initial());
    }

    static PlayerView of(BoardSnapshot informationBoard, Color sideToMove, BeliefState beliefState) {
        return new PlayerView(informationBoard, sideToMove, beliefState);
    }

    public PlayerView apply(MoveResultMessage moveResult) {
        Objects.requireNonNull(moveResult, "moveResult");
        if (!moveResult.valid()) {
            return this;
        }
        Move move = moveResult.move();
        CellState source = informationBoard.cellAt(move.from());
        CellState target = informationBoard.cellAt(move.to());
        BeliefState nextBelief = beliefState.copy();
        if (source instanceof CellState.Hidden hiddenSource && moveResult.flipResult().isPresent()) {
            nextBelief.recordKnownReveal(hiddenSource.color(), moveResult.flipResult().orElseThrow());
        }
        if (target instanceof CellState.Hidden hiddenTarget) {
            switch (moveResult.capturedPiece().kind()) {
                case KNOWN -> nextBelief.recordKnownReveal(
                        hiddenTarget.color(),
                        moveResult.capturedPiece().knownType().orElseThrow());
                case UNKNOWN -> nextBelief.recordUnknownRemoval(hiddenTarget.color());
                case NONE -> {
                    // No pool update; kept tolerant for protocol messages that omit hidden capture detail.
                }
            }
        }
        PieceType flipAs = moveResult.isFlip()
                ? moveResult.flipResult().orElseThrow(
                () -> new IllegalArgumentException("valid flip moveResult missing flipResult"))
                : null;
        BoardSnapshot next = informationBoard.apply(move.from(), move.to(), flipAs);
        return new PlayerView(next, sideToMove.opposite(), nextBelief);
    }

    public Color sideToMove() {
        return sideToMove;
    }

    public List<Move> legalMoves() {
        return RuleEngine.generateLegalMoves(informationBoard, sideToMove);
    }

    BoardSnapshot informationBoard() {
        return informationBoard;
    }

    BeliefState beliefState() {
        return beliefState.copy();
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
