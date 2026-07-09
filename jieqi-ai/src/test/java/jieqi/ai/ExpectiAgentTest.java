package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardText;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void tinyBudgetStillReturnsLegalMove() {
        PlayerView view = viewOf(BoardText.INITIAL);
        ExpectiAgent agent = new ExpectiAgent();

        Optional<Move> selected = agent.selectMove(view, TimeBudget.ofMillis(0));

        assertTrue(selected.isPresent());
        assertTrue(view.legalMoves().contains(selected.orElseThrow()));
    }

    @Test
    void unlimitedBudgetCompletesAtLeastDepthThree() {
        PlayerView view = viewOf(BoardText.INITIAL);
        ExpectiAgent agent = new ExpectiAgent();

        agent.selectMove(view, TimeBudget.unlimited());

        assertTrue(agent.lastStats().completedDepth() >= 3);
        assertFalse(agent.lastStats().timedOut());
    }

    @Test
    void lastStatsRecordsCompletedDepthAndNodes() {
        PlayerView view = viewOf(BoardText.INITIAL);
        ExpectiAgent agent = new ExpectiAgent(2);

        agent.selectMove(view, TimeBudget.unlimited());
        SearchStats stats = agent.lastStats();

        assertEquals(2, stats.completedDepth());
        assertTrue(stats.searchedNodes() > 0);
        assertTrue(stats.betaCutoffs() >= 0);
        assertFalse(stats.timedOut());
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
    void immediateKingCaptureDoesNotWasteSearch() {
        PlayerView view = viewOf("""
                4k4/9/9/9/9/9/9/9/9/K3R4 r
                """);
        ExpectiAgent agent = new ExpectiAgent();

        agent.selectMove(view, TimeBudget.unlimited());

        assertEquals(0, agent.lastStats().completedDepth());
        assertEquals(0, agent.lastStats().searchedNodes());
        assertFalse(agent.lastStats().timedOut());
    }

    @Test
    void timedOutStatsIsTrueWhenBudgetTooSmall() {
        PlayerView view = viewOf(BoardText.INITIAL);
        ExpectiAgent agent = new ExpectiAgent();

        agent.selectMove(view, TimeBudget.ofMillis(0));

        assertTrue(agent.lastStats().timedOut());
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
