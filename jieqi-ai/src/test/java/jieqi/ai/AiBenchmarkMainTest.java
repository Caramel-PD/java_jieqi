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
                "--agent", "expecti",
                "--opponents", "random",
                "--games", "1",
                "--seed", "1",
                "--maxPlies", "1"
        });
        assertFalse(opts.bothSides());

        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals(1, rows.size());
        assertEquals("red", rows.get(0).side());
    }

    @Test
    void bothSidesProducesTwoRowsPerOpponentPerSeed() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random,greedy",
                "--games", "1",
                "--seed", "1",
                "--maxPlies", "1",
                "--bothSides"
        });
        assertTrue(opts.bothSides());

        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        // 2 opponents × 2 sides = 4 rows
        assertEquals(4, rows.size());
        assertEquals("red", rows.get(0).side());
        assertEquals("black", rows.get(1).side());
        assertEquals("red", rows.get(2).side());
        assertEquals("black", rows.get(3).side());
    }

    @Test
    void blackSideWinRateUsesBlackWins() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random",
                "--games", "10",
                "--seed", "1",
                "--maxPlies", "200",
                "--bothSides"
        });

        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        AiBenchmarkMain.BenchmarkRow redSide = rows.get(0);
        AiBenchmarkMain.BenchmarkRow blackSide = rows.get(1);

        assertEquals("red", redSide.side());
        assertEquals("black", blackSide.side());

        assertEquals(redSide.wins() + redSide.losses() + redSide.draws(), redSide.games());
        assertEquals(blackSide.wins() + blackSide.losses() + blackSide.draws(), blackSide.games());
        assertEquals((double) blackSide.wins() / blackSide.games(), blackSide.winRate(), 0.001,
                "black-side winRate must use blackWins for agent");
    }

    // ================================================================
    // Part 2: multiple seeds
    // ================================================================

    @Test
    void multipleSeedsProduceIndependentRows() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random",
                "--games", "1",
                "--seeds", "10,20,30",
                "--maxPlies", "1"
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

        assertEquals(Files.readString(csv1), Files.readString(csv2),
                "same seed must produce identical CSV");
    }

    @Test
    void csvHeaderContainsSideAndSeed() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random",
                "--games", "1",
                "--seeds", "1,2",
                "--maxPlies", "1",
                "--bothSides"
        });

        String csv = AiBenchmarkMain.toCsv(AiBenchmarkMain.run(opts));
        assertTrue(csv.startsWith(AiBenchmarkMain.CSV_HEADER + "\n"));
        assertTrue(csv.contains("agent,opponent,side,games,wins,losses,draws,winRate,averagePlies,seed,maxPlies"));
    }

    // ================================================================
    // Part 3: summary
    // ================================================================

    @Test
    void summaryAggregatesBothSides() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random",
                "--games", "5",
                "--seeds", "1,2",
                "--maxPlies", "1",
                "--bothSides"
        });

        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        // 2 seeds × 2 sides = 4 rows → aggregated to 1 summary row
        String summary = AiBenchmarkMain.toSummaryCsv(rows);
        String[] lines = summary.split("\n");
        assertEquals(2, lines.length);
        assertTrue(lines[0].startsWith("agent,opponent,totalGames"));
    }

    @Test
    void summaryDoesNotMixDifferentOpponents() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random,greedy",
                "--games", "2",
                "--seed", "1",
                "--maxPlies", "1",
                "--bothSides"
        });

        String summary = AiBenchmarkMain.toSummaryCsv(AiBenchmarkMain.run(opts));
        String[] lines = summary.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[1].contains("expecti,random,"));
        assertTrue(lines[2].contains("expecti,greedy,"));
        assertFalse(lines[1].contains("greedy"));
        assertFalse(lines[2].contains("random"));
    }

    // ================================================================
    // Part 5: misc
    // ================================================================

    @Test
    void benchmarkCreatesParentDirectories() throws Exception {
        Path deep = tempDir.resolve("sub").resolve("deep").resolve("bench.csv");
        AiBenchmarkMain.main(new String[]{
                "--agent", "expecti",
                "--opponents", "random",
                "--games", "1",
                "--seed", "1",
                "--maxPlies", "1",
                "--csv", deep.toString()
        });
        assertTrue(Files.exists(deep));
    }

    // ================================================================
    // Legacy (adapted from D-04)
    // ================================================================

    @Test
    void benchmarkWritesCsvWithRequiredHeader() throws Exception {
        Path csvPath = tempDir.resolve("benchmark.csv");
        AiBenchmarkMain.main(new String[]{
                "--agent", "expecti",
                "--opponents", "random,greedy",
                "--games", "1",
                "--seed", "1",
                "--maxPlies", "1",
                "--csv", csvPath.toString()
        });
        String csv = Files.readString(csvPath, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith(AiBenchmarkMain.CSV_HEADER + "\n"));
    }

    @Test
    void benchmarkRunsExpectiAgainstThreeBaselines() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random,greedy,tactical",
                "--games", "1",
                "--seed", "1",
                "--maxPlies", "1"
        });
        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals(3, rows.size());
        assertEquals("expecti", rows.get(0).agent());
    }

    @Test
    void winRateEqualsWinsDividedByGames() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random",
                "--games", "10",
                "--seed", "1",
                "--maxPlies", "200"
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
        assertEquals(1L, opts.seeds()[0]);
        assertEquals(200, opts.maxPlies());
        assertFalse(opts.bothSides());
        assertNull(opts.csvPath());
        assertNull(opts.summaryCsvPath());
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

    @Test
    void agentCaseInsensitiveAndDashUnderscoreInsensitive() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "ExPeCtI", "--opponents", "RANDOM"
        });
        assertEquals("expecti", opts.agent());
        assertEquals("random", opts.opponents()[0]);
    }

    // ---- helpers ----

    private static String[] append(String[] base, String... extra) {
        String[] result = new String[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }
}
