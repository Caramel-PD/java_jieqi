package jieqi.ai;

import jieqi.common.Move;
import jieqi.rules.BoardText;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TacticalAgentTest {

    @Test
    void prefersCapturingKing() {
        PlayerView view = viewOf("""
                4k4/9/9/9/9/9/9/9/9/K3R4 r
                """);

        Optional<Move> selected = new TacticalAgent().selectMove(view, TimeBudget.unlimited());

        assertEquals(Optional.of(Move.parse("e0e9")), selected);
    }

    @Test
    void prefersHigherValueCapture() {
        PlayerView view = viewOf("""
                4k4/9/9/9/9/4p4/9/2r6/9/KN2R4 r
                """);

        Optional<Move> selected = new TacticalAgent().selectMove(view, TimeBudget.unlimited());

        assertEquals(Optional.of(Move.parse("b0c2")), selected);
    }

    @Test
    void returnsOnlyLegalMove() {
        PlayerView view = viewOf(BoardText.INITIAL);

        Optional<Move> selected = new TacticalAgent().selectMove(view, TimeBudget.unlimited());

        assertTrue(selected.isPresent());
        assertTrue(view.legalMoves().contains(selected.orElseThrow()));
    }

    private static PlayerView viewOf(String text) {
        BoardText.ParsedPosition position = BoardText.parse(text);
        return PlayerView.of(position.board(), position.sideToMove());
    }
}
