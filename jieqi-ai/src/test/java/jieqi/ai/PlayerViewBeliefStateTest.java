package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardText;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlayerViewBeliefStateTest {

    @Test
    void knownFlipUpdatesBeliefPool() {
        PlayerView view = viewOf(BoardText.INITIAL);

        PlayerView next = view.apply(validFlip(Move.parse("a0a1"), PieceType.ROOK));

        assertEquals(1, next.beliefState().count(Color.RED, PieceType.ROOK));
        assertEquals(2, view.beliefState().count(Color.RED, PieceType.ROOK));
    }

    @Test
    void knownHiddenCaptureUpdatesOpponentPool() {
        PlayerView view = viewOf("""
                4k4/9/9/x8/9/9/9/9/9/R3K4 r
                """);

        PlayerView next = view.apply(validCapture(Move.parse("a0a6"),
                MoveResultMessage.CapturedPiece.known(PieceType.PAWN)));

        assertEquals(4, next.beliefState().count(Color.BLACK, PieceType.PAWN));
        assertEquals(5, view.beliefState().count(Color.BLACK, PieceType.PAWN));
    }

    @Test
    void unknownHiddenCaptureRecordsUnknownRemoval() {
        PlayerView view = viewOf("""
                4k4/9/9/x8/9/9/9/9/9/R3K4 r
                """);

        PlayerView next = view.apply(validCapture(Move.parse("a0a6"),
                MoveResultMessage.CapturedPiece.unknown()));

        assertEquals(1, next.beliefState().unknownRemovals(Color.BLACK));
        assertEquals(5, next.beliefState().count(Color.BLACK, PieceType.PAWN));
    }

    @Test
    void capturedRevealedPieceIsNotRemovedTwice() {
        BeliefState belief = BeliefState.initial();
        belief.recordKnownReveal(Color.BLACK, PieceType.PAWN);
        PlayerView view = viewOf("""
                4k4/9/9/p8/9/9/9/9/9/R3K4 r
                """, belief);

        PlayerView next = view.apply(validCapture(Move.parse("a0a6"),
                MoveResultMessage.CapturedPiece.known(PieceType.PAWN)));

        assertEquals(4, next.beliefState().count(Color.BLACK, PieceType.PAWN));
    }

    @Test
    void invalidMoveDoesNotChangeBelief() {
        PlayerView view = viewOf(BoardText.INITIAL);

        PlayerView next = view.apply(new MoveResultMessage(
                false,
                Move.parse("a0a1"),
                true,
                Optional.of(PieceType.ROOK),
                MoveResultMessage.CapturedPiece.none()));

        assertEquals(2, next.beliefState().count(Color.RED, PieceType.ROOK));
        assertEquals(view.sideToMove(), next.sideToMove());
        assertEquals(view.isOccupied(jieqi.common.Coord.parse("a0")), next.isOccupied(jieqi.common.Coord.parse("a0")));
    }

    @Test
    void previousPlayerViewRemainsUnchanged() {
        PlayerView view = viewOf(BoardText.INITIAL);

        PlayerView next = view.apply(validFlip(Move.parse("a0a1"), PieceType.ROOK));

        assertEquals(2, view.beliefState().count(Color.RED, PieceType.ROOK));
        assertEquals(1, next.beliefState().count(Color.RED, PieceType.ROOK));
        assertFalse(next.isOccupied(jieqi.common.Coord.parse("a0")));
    }

    private static PlayerView viewOf(String text) {
        BoardText.ParsedPosition position = BoardText.parse(text);
        return PlayerView.of(position.board(), position.sideToMove());
    }

    private static PlayerView viewOf(String text, BeliefState belief) {
        BoardText.ParsedPosition position = BoardText.parse(text);
        return PlayerView.of(position.board(), position.sideToMove(), belief);
    }

    private static MoveResultMessage validFlip(Move move, PieceType flipResult) {
        return new MoveResultMessage(true, move, true, Optional.of(flipResult), MoveResultMessage.CapturedPiece.none());
    }

    private static MoveResultMessage validCapture(Move move, MoveResultMessage.CapturedPiece capturedPiece) {
        return new MoveResultMessage(true, move, false, Optional.empty(), capturedPiece);
    }
}
