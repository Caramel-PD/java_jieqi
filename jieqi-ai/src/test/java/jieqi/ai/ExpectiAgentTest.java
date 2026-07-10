package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardText;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpectiAgentTest {

    @Test
    void expectiAgentReturnsLegalMove() {
        PlayerView view = viewOf(BoardText.INITIAL);

        Optional<Move> selected = new ExpectiAgent(1).selectMove(view, TimeBudget.unlimited());

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
        PlayerView view = viewOf("""
                4k4/9/9/9/9/9/9/9/4P4/4K4 r
                """);
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
    void chanceNodeUsesWeightedExpectedScore() {
        PlayerView view = viewOf("""
                4k4/9/9/9/4p4/9/X8/9/9/4K4 r
                """);
        Move hiddenMove = Move.parse("a3a4");
        BeliefState belief = beliefKeeping(Color.RED, PieceType.ROOK, PieceType.PAWN);
        ExpectiAgent agent = new ExpectiAgent(1, new PositionEvaluator(), belief);

        int rookScore = agent.scoreMoveAsRevealForTesting(view, hiddenMove, 1, belief, PieceType.ROOK);
        int pawnScore = agent.scoreMoveAsRevealForTesting(view, hiddenMove, 1, belief, PieceType.PAWN);
        int expected = (int) Math.round((rookScore * 2L + pawnScore * 5L) / 7.0);

        int chanceScore = agent.scoreMoveForTesting(view, hiddenMove, 1, belief, -1_000_000_000, 1_000_000_000);

        assertEquals(7, belief.poolSize(Color.RED));
        assertEquals(2, belief.count(Color.RED, PieceType.ROOK));
        assertEquals(5, belief.count(Color.RED, PieceType.PAWN));
        assertTrue(rookScore > pawnScore);
        assertEquals(expected, chanceScore);
    }

    @Test
    void chanceNodeDoesNotUseCutoffBoundAsExactScore() {
        PlayerView view = viewOf("""
                4k4/9/9/9/4p4/9/X8/9/9/R3K4 r
                """);
        Move hiddenMove = Move.parse("a3a4");
        BeliefState belief = beliefKeeping(Color.RED, PieceType.ROOK, PieceType.PAWN);
        ExpectiAgent agent = new ExpectiAgent(2, new PositionEvaluator(), belief);

        int exactScore = agent.scoreMoveForTesting(view, hiddenMove, 2, belief, -1_000_000_000, 1_000_000_000);
        int scoreWithParentAlpha = agent.scoreMoveForTesting(view, hiddenMove, 2, belief, 500_000, 1_000_000_000);

        assertEquals(exactScore, scoreWithParentAlpha);
    }

    @Test
    void chanceBranchesDoNotMutateSiblingBeliefs() {
        BeliefState belief = BeliefState.initial();
        PlayerView view = viewOf(BoardText.INITIAL);
        ExpectiAgent agent = new ExpectiAgent(1, new PositionEvaluator(), belief);

        agent.selectMove(view, TimeBudget.unlimited());

        assertEquals(15, belief.poolSize(Color.RED));
        assertEquals(2, belief.count(Color.RED, PieceType.ROOK));
        assertEquals(5, belief.count(Color.RED, PieceType.PAWN));
        assertEquals(0, belief.unknownRemovals(Color.RED));
    }

    @Test
    void hiddenMoveSearchStillReturnsLegalMove() {
        PlayerView view = viewOf(BoardText.INITIAL);
        ExpectiAgent agent = new ExpectiAgent(1);

        Optional<Move> selected = agent.selectMove(view, TimeBudget.unlimited());

        assertTrue(view.legalMoves().stream().anyMatch(move -> view.isHidden(move.from())));
        assertTrue(selected.isPresent());
        assertTrue(view.legalMoves().contains(selected.orElseThrow()));
        assertTrue(agent.lastStats().searchedNodes() > view.legalMoves().size());
    }

    @Test
    void exhaustedPoolDoesNotCrashSearch() {
        BeliefState belief = BeliefState.initial();
        exhaustPool(belief, Color.RED);
        PlayerView view = viewOf(BoardText.INITIAL);
        ExpectiAgent agent = new ExpectiAgent(1, new PositionEvaluator(), belief);

        Optional<Move> selected = assertDoesNotThrow(() -> agent.selectMove(view, TimeBudget.unlimited()));

        assertTrue(selected.isPresent());
        assertTrue(view.legalMoves().contains(selected.orElseThrow()));
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

    private static void exhaustPool(BeliefState belief, Color side) {
        for (PieceType type : PieceType.values()) {
            while (belief.count(side, type) > 0) {
                belief.recordKnownReveal(side, type);
            }
        }
    }

    private static BeliefState beliefKeeping(Color side, PieceType... keptTypes) {
        Set<PieceType> kept = EnumSet.noneOf(PieceType.class);
        kept.addAll(Set.of(keptTypes));
        BeliefState belief = BeliefState.initial();
        for (PieceType type : PieceType.values()) {
            if (type != PieceType.KING && !kept.contains(type)) {
                while (belief.count(side, type) > 0) {
                    belief.recordKnownReveal(side, type);
                }
            }
        }
        return belief;
    }
}
