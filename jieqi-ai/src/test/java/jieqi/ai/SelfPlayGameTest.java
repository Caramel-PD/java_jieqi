package jieqi.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
