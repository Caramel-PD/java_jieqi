package jieqi.ai;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiMainTest {

    @Test
    void defaultArgsUseLocalServerAndGreedyAgent() {
        AiMain.CliOptions options = AiMain.parseArgs(new String[0]);

        assertEquals(URI.create("ws://localhost:8887"), options.config().serverUrl());
        assertEquals("ai", options.config().userId());
        assertEquals("ai", options.config().password());
        assertEquals("AI", options.config().nickname());
        assertEquals(10_000L, options.config().thinkTimeMillis());
        assertFalse(options.config().registerOnConnect());
        assertEquals("greedy", options.agentType());
    }

    @Test
    void parsesCommandLineOverrides() {
        AiMain.CliOptions options = AiMain.parseArgs(new String[] {
                "--serverUrl=ws://localhost:9999",
                "--userId", "ai1",
                "--password", "123456",
                "--nickname", "AIPlayer",
                "--agent", "random",
                "--thinkTimeMillis", "250",
                "--register"
        });

        assertEquals(URI.create("ws://localhost:9999"), options.config().serverUrl());
        assertEquals("ai1", options.config().userId());
        assertEquals("123456", options.config().password());
        assertEquals("AIPlayer", options.config().nickname());
        assertEquals(250L, options.config().thinkTimeMillis());
        assertTrue(options.config().registerOnConnect());
        assertEquals("random", options.agentType());
    }

    @Test
    void selectsConfiguredAgent() {
        assertInstanceOf(RandomAgent.class, AiMain.createAgent("random"));
        assertInstanceOf(GreedyAgent.class, AiMain.createAgent("greedy"));
    }

    @Test
    void rejectsUnknownAgent() {
        assertThrows(IllegalArgumentException.class, () -> AiMain.createAgent("expecti"));
    }

    @Test
    void helpDocumentsRunnableJarAndTwoAiCommands() throws InterruptedException {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

            AiMain.main(new String[] {"--help"});
        } finally {
            System.setOut(originalOut);
        }

        String help = out.toString(StandardCharsets.UTF_8);
        assertTrue(help.contains("java -jar jieqi-ai/target/jieqi-ai.jar"));
        assertTrue(help.contains("--serverUrl ws://localhost:8887 --userId ai1 --password ai1 --nickname AI1 --register"));
        assertTrue(help.contains("--serverUrl ws://localhost:8887 --userId ai2 --password ai2 --nickname AI2 --register"));
        assertTrue(help.contains("agent = greedy"));
    }
}
