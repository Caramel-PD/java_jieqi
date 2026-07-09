package jieqi.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfPlayExperimentTest {

    @TempDir
    private Path tempDir;

    @Test
    void matrixModeGeneratesFourRows() {
        SelfPlayExperiment experiment = SelfPlayExperiment.matrix(1, 1L, 1);

        assertEquals(4, experiment.results().size());
        assertEquals("random", experiment.results().get(0).redAgent());
        assertEquals("random", experiment.results().get(0).blackAgent());
        assertEquals("greedy", experiment.results().get(3).redAgent());
        assertEquals("greedy", experiment.results().get(3).blackAgent());
    }

    @Test
    void csvHeaderContainsRequiredColumns() {
        String csv = SelfPlayExperiment.matrix(1, 1L, 1).toCsv();

        assertTrue(csv.startsWith(SelfPlayExperiment.CSV_HEADER + "\n"));
        assertTrue(csv.contains("redAgent,blackAgent,games,redWins,blackWins,draws,averagePlies,seed,maxPlies"));
    }

    @Test
    void sameSeedProducesSameCsv() {
        String first = SelfPlayExperiment.matrix(2, 9L, 10).toCsv();
        String second = SelfPlayExperiment.matrix(2, 9L, 10).toCsv();

        assertEquals(first, second);
    }

    @Test
    void csvOptionWritesUtf8File() throws Exception {
        Path csvPath = tempDir.resolve("self-play.csv");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

            SelfPlayMain.main(new String[] {
                    "--matrix",
                    "--games", "1",
                    "--seed", "1",
                    "--maxPlies", "1",
                    "--csv", csvPath.toString()
            });
        } finally {
            System.setOut(originalOut);
        }

        String csv = Files.readString(csvPath, StandardCharsets.UTF_8);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains(csvPath.toString()));
        assertTrue(csv.startsWith(SelfPlayExperiment.CSV_HEADER + "\n"));
        assertEquals(5, csv.lines().count());
    }

    @Test
    void normalSingleMatchupModeStillPrintsReport() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

            SelfPlayMain.main(new String[] {
                    "--games", "1",
                    "--red", "greedy",
                    "--black", "random",
                    "--seed", "1",
                    "--maxPlies", "1"
            });
        } finally {
            System.setOut(originalOut);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("games=1"));
        assertTrue(text.contains("redAgent=greedy"));
        assertTrue(text.contains("blackAgent=random"));
        assertTrue(text.contains("averagePlies="));
    }

    @Test
    void helpDocumentsMatrixAndCsvOptions() {
        String help = SelfPlayMain.usage();

        assertTrue(help.contains("--matrix"));
        assertTrue(help.contains("--csv target/self-play.csv"));
        assertTrue(help.contains("SelfPlayMain --matrix --games 20"));
    }
}
