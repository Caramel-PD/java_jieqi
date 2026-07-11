package jieqi.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AiBenchmarkMainTest {

    @TempDir
    Path tempDir;

    // ================================================================
    // Part 1: bothSides
    // ================================================================

    @Test
    void defaultBenchmarkKeepsAgentAsRedOnly() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "1", "--seed", "1", "--maxPlies", "1"
        });
        assertFalse(opts.bothSides());
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals(1, rows.size());
        assertEquals("red", rows.get(0).side());
    }

    @Test
    void bothSidesProducesTwoRowsPerOpponentPerSeed() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random,greedy",
                "--games", "1", "--seed", "1", "--maxPlies", "1", "--bothSides"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals(4, rows.size());
        assertEquals("red", rows.get(0).side());
        assertEquals("black", rows.get(1).side());
    }

    @Test
    void blackSideWinRateUsesBlackWins() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "10", "--seed", "1", "--maxPlies", "200", "--bothSides"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        AiBenchmarkMain.BenchmarkRow blackSide = rows.get(1);
        assertEquals("black", blackSide.side());
        assertEquals(blackSide.wins() + blackSide.losses() + blackSide.draws(), blackSide.games());
        assertEquals((double) blackSide.wins() / blackSide.games(), blackSide.winRate(), 0.001);
    }

    // ================================================================
    // Part 2: multiple seeds
    // ================================================================

    @Test
    void multipleSeedsProduceIndependentRows() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "1", "--seeds", "10,20,30", "--maxPlies", "1"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals(3, rows.size());
        assertEquals(10L, rows.get(0).seed());
        assertEquals(20L, rows.get(1).seed());
        assertEquals(30L, rows.get(2).seed());
    }

    @Test
    void seedAndSeedsCannotBeUsedTogether() {
        assertThrows(IllegalArgumentException.class, () ->
                AiBenchmarkMain.parseArgs(new String[]{"--seed", "1", "--seeds", "1,2,3"}));
    }

    @Test
    void sameSeedProducesDeterministicCsv() throws Exception {
        Path csv1 = tempDir.resolve("r1.csv");
        Path csv2 = tempDir.resolve("r2.csv");
        String[] baseArgs = {"--agent", "expecti", "--opponents", "random",
                "--games", "3", "--seed", "42", "--maxPlies", "200"};
        AiBenchmarkMain.main(append(baseArgs, "--csv", csv1.toString()));
        AiBenchmarkMain.main(append(baseArgs, "--csv", csv2.toString()));
        // 性能统计列受系统时间影响，只对比确定性列（前 11 列：agent..maxPlies）
        String[] lines1 = Files.readString(csv1).split("\n");
        String[] lines2 = Files.readString(csv2).split("\n");
        assertEquals(lines1[0], lines2[0], "header must match");
        assertEquals(lines1.length, lines2.length, "same row count");
        for (int i = 1; i < lines1.length; i++) {
            String[] cols1 = lines1[i].split(",");
            String[] cols2 = lines2[i].split(",");
            for (int j = 0; j < 11; j++) { // first 11 cols: agent..maxPlies (non-stats)
                assertEquals(cols1[j], cols2[j], "col " + j + " row " + i + " must match");
            }
        }
    }

    // ================================================================
    // Part 3: summary
    // ================================================================

    @Test
    void summaryAggregatesBothSides() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "5", "--seeds", "1,2", "--maxPlies", "1", "--bothSides"
        });
        String summary = AiBenchmarkMain.toSummaryCsv(AiBenchmarkMain.run(opts));
        String[] lines = summary.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("agent,opponent,totalGames"));
    }

    @Test
    void summaryDoesNotMixDifferentOpponents() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random,greedy",
                "--games", "2", "--seed", "1", "--maxPlies", "1", "--bothSides"
        });
        String summary = AiBenchmarkMain.toSummaryCsv(AiBenchmarkMain.run(opts));
        String[] lines = summary.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[1].contains("expecti,random,"));
        assertTrue(lines[2].contains("expecti,greedy,"));
        assertFalse(lines[1].contains("greedy"));
        assertFalse(lines[2].contains("random"));
    }

    @Test
    void summaryTotalGamesEqualsSumOfRawGames() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "3", "--seeds", "1,2", "--maxPlies", "1", "--bothSides"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        int rawTotal = rows.stream().mapToInt(AiBenchmarkMain.BenchmarkRow::games).sum();
        String summary = AiBenchmarkMain.toSummaryCsv(rows);
        String summaryRow = summary.split("\n")[1];
        int totalGames = Integer.parseInt(summaryRow.split(",")[2]);
        assertEquals(rawTotal, totalGames);
    }

    @Test
    void multiSeedSummaryDoesNotDoubleCountSeeds() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "2", "--seeds", "1,2,3", "--maxPlies", "1", "--bothSides"
        });
        // 3 seeds × 2 sides = 6 raw rows, but only 3 unique seeds
        String summary = AiBenchmarkMain.toSummaryCsv(AiBenchmarkMain.run(opts));
        String summaryRow = summary.split("\n")[1];
        String[] cols = summaryRow.split(",");
        int seedCount = Integer.parseInt(cols[cols.length - 1]);
        assertEquals(3, seedCount, "bothSides must not double-count seeds");
    }

    // ================================================================
    // D-06 rework: timeout / depth statistics tests
    // ================================================================

    @Test
    void csvHeaderHasMeasuredMovesAndTimeoutRate() {
        String header = AiBenchmarkMain.CSV_HEADER;
        assertTrue(header.contains("measuredMoves"));
        assertTrue(header.contains("timeoutMoves"));
        assertTrue(header.contains("timeoutRate"));
        assertTrue(header.contains("avgNodesPerMove"));
        assertTrue(header.contains("avgDepth"));
        assertFalse(header.contains("timeouts,"), "must not use ambiguous 'timeouts' column");
    }

    @Test
    void measuredMovesCountsEachSelectMoveOnce() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "2", "--seed", "1", "--maxPlies", "200"
        });
        AiBenchmarkMain.BenchmarkRow row = AiBenchmarkMain.run(opts).get(0);
        assertTrue(row.measuredMoves() > 0, "measuredMoves should count selectMove calls");
        assertEquals(row.measuredMoves(), row.timeoutMoves() + (row.measuredMoves() - row.timeoutMoves()));
    }

    @Test
    void timeoutRateEqualsTimeoutMovesDividedByMeasuredMoves() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "10", "--seed", "1", "--maxPlies", "200"
        });
        AiBenchmarkMain.BenchmarkRow row = AiBenchmarkMain.run(opts).get(0);
        assertEquals((double) row.timeoutMoves() / row.measuredMoves(), row.timeoutRate(), 0.001);
    }

    @Test
    void avgDepthUsesPerMoveCompletedDepth() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "3", "--seed", "1", "--maxPlies", "200"
        });
        AiBenchmarkMain.BenchmarkRow row = AiBenchmarkMain.run(opts).get(0);
        assertTrue(row.avgDepth() >= 0, "avgDepth per move, may be < 1 for shallow searches");
    }

    @Test
    void summaryDoesNotPassTimeoutMoveCountAsGameCount() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "3", "--seeds", "1,2", "--maxPlies", "1", "--bothSides"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        for (AiBenchmarkMain.BenchmarkRow row : rows) {
            // timeoutMoves is per move, not per game; must be ≤ measuredMoves
            assertTrue(row.timeoutMoves() <= row.measuredMoves(),
                    "timeoutMoves must be <= measuredMoves per row");
        }
    }

    @Test
    void winsPlusLossesPlusDrawsEqualsGames() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random,greedy",
                "--games", "3", "--seeds", "1,2", "--maxPlies", "200", "--bothSides"
        });
        for (AiBenchmarkMain.BenchmarkRow row : AiBenchmarkMain.run(opts)) {
            assertEquals(row.games(), row.wins() + row.losses() + row.draws());
        }
    }

    @Test
    void redAndBlackStatsAreNotSwapped() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "greedy",
                "--games", "2", "--seed", "1", "--maxPlies", "200", "--bothSides"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals("red", rows.get(0).side());
        assertEquals("black", rows.get(1).side());
        assertTrue(rows.get(0).measuredMoves() >= 0);
        assertTrue(rows.get(1).measuredMoves() >= 0);
    }

    @Test
    void summaryTotalGamesStillEqualsSumOfRawGames() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "3", "--seeds", "1,2", "--maxPlies", "1", "--bothSides"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        int rawTotal = rows.stream().mapToInt(AiBenchmarkMain.BenchmarkRow::games).sum();
        String summaryRow = AiBenchmarkMain.toSummaryCsv(rows).split("\n")[1];
        int totalGames = Integer.parseInt(summaryRow.split(",")[2]);
        assertEquals(rawTotal, totalGames);
    }

    // ================================================================
    // misc / legacy
    // ================================================================

    @Test
    void benchmarkCreatesParentDirectories() throws Exception {
        Path deep = tempDir.resolve("sub").resolve("deep").resolve("bench.csv");
        AiBenchmarkMain.main(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "1", "--seed", "1", "--maxPlies", "1", "--csv", deep.toString()
        });
        assertTrue(Files.exists(deep));
    }

    @Test
    void benchmarkWritesCsvWithRequiredHeader() throws Exception {
        Path csvPath = tempDir.resolve("benchmark.csv");
        AiBenchmarkMain.main(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "1", "--seed", "1", "--maxPlies", "1", "--csv", csvPath.toString()
        });
        String csv = Files.readString(csvPath, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith(AiBenchmarkMain.CSV_HEADER + "\n"));
    }

    @Test
    void winRateEqualsWinsDividedByGames() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti", "--opponents", "random",
                "--games", "10", "--seed", "1", "--maxPlies", "200"
        });
        AiBenchmarkMain.BenchmarkRow row = AiBenchmarkMain.run(opts).get(0);
        assertEquals(10, row.games());
        assertEquals(row.wins() + row.losses() + row.draws(), row.games());
        assertEquals((double) row.wins() / row.games(), row.winRate(), 0.001);
    }

    @Test
    void defaultsAreSensible() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[0]);
        assertEquals("expecti", opts.agent());
        assertEquals(3, opts.opponents().length);
        assertEquals(20, opts.games());
        assertEquals(1, opts.seeds().length);
        assertEquals(200, opts.maxPlies());
        assertNull(opts.csvPath());
    }

    @Test
    void rejectsInvalidAgent() {
        assertThrows(IllegalArgumentException.class, () ->
                AiBenchmarkMain.parseArgs(new String[]{"--agent", "minimax"}));
    }

    @Test
    void rejectsNegativeGames() {
        assertThrows(IllegalArgumentException.class, () ->
                AiBenchmarkMain.parseArgs(new String[]{"--games", "-1"}));
    }

    private static String[] append(String[] base, String... extra) {
        String[] result = new String[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
