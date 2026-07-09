package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardText;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectiAgentTest {

    @Test
    void expectiAgentReturnsLegalMove() {
        PlayerView view = viewOf(BoardText.INITIAL);

        Optional<Move> selected = new ExpectiAgent().selectMove(view, TimeBudget.unlimited());

        assertTrue(selected.isPresent());
        assertTrue(view.legalMoves().contains(selected.orElseThrow()));
    }

    @Test
    void expectiAgentPrefersImmediateKingCapture() {
        PlayerView view = viewOf("""
                4k4/9/9/9/9/9/9/9/9/K3R4 r
                """);

        Optional<Move> selected = new ExpectiAgent().selectMove(view, TimeBudget.unlimited());

        assertEquals(Optional.of(Move.parse("e0e9")), selected);
    }

    @Test
    void beliefStateCopyDoesNotMutateOriginal() {
        BeliefState original = BeliefState.initial();
        BeliefState copy = original.copy();

        copy.recordKnownReveal(Color.RED, PieceType.ROOK);
        copy.recordUnknownRemoval(Color.BLACK);

        assertEquals(2, original.count(Color.RED, PieceType.ROOK));
        assertEquals(0, original.unknownRemovals(Color.BLACK));
        assertEquals(1, copy.count(Color.RED, PieceType.ROOK));
        assertEquals(1, copy.unknownRemovals(Color.BLACK));
    }

    @Test
    void aiMainCreatesExpectiAgent() {
        assertInstanceOf(ExpectiAgent.class, AiMain.createAgent("expecti"));
    }

    @Test
    void selfPlayMainSupportsExpectiAgent() {
        SelfPlayResult result = SelfPlayMain.run(new SelfPlayMain.CliOptions(1, "expecti", "random", 1L, 1));

        assertEquals(1, result.games());
        assertEquals("expecti", result.redAgent());
        assertEquals("random", result.blackAgent());
    }

    private static PlayerView viewOf(String text) {
        BoardText.ParsedPosition position = BoardText.parse(text);
        return PlayerView.of(position.board(), position.sideToMove());
    }
}
