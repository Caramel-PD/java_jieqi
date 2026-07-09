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
        assertTrue(csv.startsWith(AiBenchmarkMain.CSV_HEADER + "\n"),
                "CSV must start with required header");
        assertTrue(csv.contains("agent,opponent,games,wins,losses,draws,winRate,averagePlies,seed,maxPlies"));
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

        assertEquals("expecti", opts.agent());
        assertEquals(3, opts.opponents().length);
        assertEquals("random", opts.opponents()[0]);
        assertEquals("greedy", opts.opponents()[1]);
        assertEquals("tactical", opts.opponents()[2]);

        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals(3, rows.size());
        assertEquals("expecti", rows.get(0).agent());
        assertEquals("random", rows.get(0).opponent());
        assertEquals("greedy", rows.get(1).opponent());
        assertEquals("tactical", rows.get(2).opponent());
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

        List<AiBenchmarkMain.BenchmarkRow> rows = AiBenchmarkMain.run(opts);
        assertEquals(1, rows.size());

        AiBenchmarkMain.BenchmarkRow row = rows.get(0);
        assertEquals(10, row.games());
        assertEquals(row.wins() + row.losses() + row.draws(), row.games(),
                "wins + losses + draws must equal games");
        assertEquals((double) row.wins() / row.games(), row.winRate(), 0.001,
                "winRate must equal wins / games");
    }

    @Test
    void sameSeedProducesStableBenchmarkCsv() throws Exception {
        Path csv1 = tempDir.resolve("run1.csv");
        Path csv2 = tempDir.resolve("run2.csv");

        AiBenchmarkMain.main(new String[]{
                "--agent", "expecti",
                "--opponents", "random,greedy",
                "--games", "3",
                "--seed", "42",
                "--maxPlies", "200",
                "--csv", csv1.toString()
        });

        AiBenchmarkMain.main(new String[]{
                "--agent", "expecti",
                "--opponents", "random,greedy",
                "--games", "3",
                "--seed", "42",
                "--maxPlies", "200",
                "--csv", csv2.toString()
        });

        String content1 = Files.readString(csv1, StandardCharsets.UTF_8);
        String content2 = Files.readString(csv2, StandardCharsets.UTF_8);
        assertEquals(content1, content2,
                "same seed and params must produce identical CSV");
    }

    @Test
    void csvWithoutPathOptionPrintsToStdout() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

            AiBenchmarkMain.main(new String[]{
                    "--agent", "expecti",
                    "--opponents", "random",
                    "--games", "1",
                    "--seed", "1",
                    "--maxPlies", "1"
            });
        } catch (Exception e) {
            fail("should not throw", e);
        } finally {
            System.setOut(originalOut);
        }

        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains(AiBenchmarkMain.CSV_HEADER),
                "stdout should contain CSV header");
        assertTrue(output.contains("expecti,random"),
                "stdout should contain benchmark data");
    }

    @Test
    void parsesAllRequiredOptions() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "expecti",
                "--opponents", "random,greedy",
                "--games", "20",
                "--seed", "42",
                "--maxPlies", "200",
                "--csv", "target/bench.csv"
        });

        assertEquals("expecti", opts.agent());
        assertEquals(2, opts.opponents().length);
        assertEquals("random", opts.opponents()[0]);
        assertEquals("greedy", opts.opponents()[1]);
        assertEquals(20, opts.games());
        assertEquals(42L, opts.seed());
        assertEquals(200, opts.maxPlies());
        assertEquals("target/bench.csv", opts.csvPath());
    }

    @Test
    void defaultsAreSensible() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[0]);

        assertEquals("expecti", opts.agent());
        assertEquals(3, opts.opponents().length);
        assertEquals("random", opts.opponents()[0]);
        assertEquals("greedy", opts.opponents()[1]);
        assertEquals("tactical", opts.opponents()[2]);
        assertEquals(20, opts.games());
        assertEquals(1L, opts.seed());
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

    @Test
    void benchmarkRowValidationEnforcesInvariants() {
        assertThrows(IllegalArgumentException.class, () ->
                new AiBenchmarkMain.BenchmarkRow("e", "r", 2, 1, 0, 0, 0.5, 10.0, 1L, 200));
        assertThrows(IllegalArgumentException.class, () ->
                new AiBenchmarkMain.BenchmarkRow("e", "r", 2, 3, 0, 0, 1.5, 10.0, 1L, 200));
    }

    @Test
    void agentCaseInsensitiveAndDashUnderscoreInsensitive() {
        AiBenchmarkMain.CliOptions opts = AiBenchmarkMain.parseArgs(new String[]{
                "--agent", "ExPeCtI",
                "--opponents", "RANDOM"
        });
        assertEquals("expecti", opts.agent());
        assertEquals("random", opts.opponents()[0]);
    }
}
