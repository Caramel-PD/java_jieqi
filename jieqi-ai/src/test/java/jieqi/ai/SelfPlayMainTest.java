package jieqi.ai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelfPlayMainTest {

    @Test
    void parsesCommandLineOptions() {
        SelfPlayMain.CliOptions options = SelfPlayMain.parseArgs(new String[] {
                "--games", "20",
                "--red", "greedy",
                "--black", "random",
                "--seed", "1",
                "--maxPlies", "200"
        });

        assertEquals(20, options.games());
        assertEquals("greedy", options.redAgent());
        assertEquals("random", options.blackAgent());
        assertEquals(1L, options.seed());
        assertEquals(200, options.maxPlies());
    }

    @Test
    void createsSupportedAgents() {
        assertInstanceOf(RandomAgent.class, SelfPlayMain.createAgent("random", 1L));
        assertInstanceOf(GreedyAgent.class, SelfPlayMain.createAgent("greedy", 1L));
        assertInstanceOf(TacticalAgent.class, SelfPlayMain.createAgent("tactical", 1L));
    }

    @Test
    void rejectsUnsupportedAgent() {
        assertThrows(IllegalArgumentException.class, () -> SelfPlayMain.createAgent("expecti", 1L));
    }

    @Test
    void supportsAllRequiredMatchups() {
        String[] agents = {"random", "greedy", "tactical"};
        for (String red : agents) {
            for (String black : agents) {
                SelfPlayResult result = SelfPlayMain.run(new SelfPlayMain.CliOptions(1, red, black, 5L, 1));

                assertEquals(1, result.games());
                assertEquals(red, result.redAgent());
                assertEquals(black, result.blackAgent());
            }
        }
    }

    @Test
    void mainPrintsStatistics() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

            SelfPlayMain.main(new String[] {"--games", "1", "--red", "greedy", "--black", "random", "--seed", "1",
                    "--maxPlies", "1"});
        } finally {
            System.setOut(originalOut);
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("games=1"));
        assertTrue(text.contains("redAgent=greedy"));
        assertTrue(text.contains("blackAgent=random"));
        assertTrue(text.contains("averagePlies="));
    }
}
