package jieqi.ai;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardText;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfPlayGameTest {

    @Test
    void randomVsRandomRunsThreeGames() {
        SelfPlayResult result = SelfPlayMain.run(
                new SelfPlayMain.CliOptions(3, "random", "random", 1L, 50));

        assertEquals(3, result.games());
        assertEquals(3, result.redWins() + result.blackWins() + result.draws());
    }

    @Test
    void greedyVsRandomRunsThreeGames() {
        SelfPlayResult result = SelfPlayMain.run(
                new SelfPlayMain.CliOptions(3, "greedy", "random", 2L, 50));

        assertEquals(3, result.games());
        assertEquals(3, result.redWins() + result.blackWins() + result.draws());
    }

    @Test
    void sameSeedProducesSameStatistics() {
        SelfPlayMain.CliOptions options = new SelfPlayMain.CliOptions(3, "random", "greedy", 7L, 60);

        SelfPlayResult first = SelfPlayMain.run(options);
        SelfPlayResult second = SelfPlayMain.run(options);

        assertEquals(first, second);
    }

    @Test
    void tinyMaxPliesProducesDraws() {
        SelfPlayResult result = SelfPlayMain.run(
                new SelfPlayMain.CliOptions(3, "greedy", "greedy", 3L, 1));

        assertEquals(3, result.draws());
        assertEquals(1.0, result.averagePlies());
    }

    @Test
    void reportContainsRequiredFields() {
        String report = new SelfPlayResult(3, "greedy", "random", 1, 1, 1, 30).toReport();

        assertTrue(report.contains("games="));
        assertTrue(report.contains("redWins="));
        assertTrue(report.contains("blackWins="));
        assertTrue(report.contains("draws="));
        assertTrue(report.contains("averagePlies="));
    }

    @Test
    void hiddenCaptureGivesCaptorKnownPieceAndVictimUnknownPiece() {
        Move captureHidden = new Move(new Coord(1, 8), new Coord(1, 9));
        CapturingAgent red = new CapturingAgent(captureHidden);
        CapturingAgent black = new CapturingAgent(captureHidden);
        SelfPlayGame game = new SelfPlayGame(
                red,
                black,
                BoardText.parse("1x2k4/1R7/9/9/4p4/9/9/9/9/4K4 r"),
                SelfPlayGame.hiddenPoolsForTesting(PieceType.ROOK),
                1);

        SelfPlayGame.PlayedGame played = game.play();

        assertEquals(1, played.plies());
        assertEquals(BoardText.format(played.redView().informationBoard(), played.redView().sideToMove()),
                BoardText.format(played.blackView().informationBoard(), played.blackView().sideToMove()));
        assertEquals(1, red.lastView().beliefState().count(Color.BLACK, PieceType.ROOK)
                - played.redView().beliefState().count(Color.BLACK, PieceType.ROOK));
        assertEquals(1, played.blackView().beliefState().unknownRemovals(Color.BLACK));
        assertEquals(BeliefState.initial().count(Color.BLACK, PieceType.ROOK),
                played.blackView().beliefState().count(Color.BLACK, PieceType.ROOK));
        assertEquals(15, game.remainingHiddenPiecesForTesting(Color.BLACK)
                + played.blackView().beliefState().unknownRemovals(Color.BLACK));
    }

    @Test
    void selfPlayMainExpectiAgentUsesDefaultDepthDuringSelfPlay() {
        Agent created = SelfPlayMain.createAgent("expecti", 1L);
        ExpectiAgent expecti = assertInstanceOf(ExpectiAgent.class, created);
        SelfPlayGame game = new SelfPlayGame(
                expecti,
                new RandomAgent(1L),
                BoardText.parse("4k4/9/9/9/4p4/9/9/9/4R4/4K4 r"),
                SelfPlayGame.hiddenPoolsForTesting(PieceType.ROOK),
                1);

        game.play();

        assertEquals(ExpectiAgent.DEFAULT_MAX_DEPTH, expecti.lastStats().completedDepth());
        assertEquals(3, expecti.lastStats().completedDepth());
        assertFalse(expecti.lastStats().timedOut());
    }

    private static final class CapturingAgent implements Agent {
        private final Move move;
        private PlayerView lastView;

        private CapturingAgent(Move move) {
            this.move = move;
        }

        @Override
        public Optional<Move> selectMove(PlayerView view, TimeBudget budget) {
            this.lastView = view;
            return Optional.of(move);
        }

        private PlayerView lastView() {
            return lastView;
        }
    }
}
