package jieqi.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiClientConfigTest {

    @Test
    void defaultsMatchLocalServerAndBasicAiIdentity() {
        AiClientConfig config = AiClientConfig.defaults();

        assertEquals(URI.create("ws://localhost:8887"), config.serverUrl());
        assertEquals("ai", config.userId());
        assertEquals("ai", config.password());
        assertEquals("AI", config.nickname());
        assertEquals(10_000L, config.thinkTimeMillis());
        assertFalse(config.registerOnConnect());
        assertEquals("pve", config.mode());
    }

    @Test
    void acceptsSupportedMatchModes() {
        AiClientConfig pve = new AiClientConfig(
                AiClientConfig.DEFAULT_SERVER_URL,
                "ai1",
                "pw",
                "AI",
                10_000L,
                false,
                "pve");
        AiClientConfig aivai = new AiClientConfig(
                AiClientConfig.DEFAULT_SERVER_URL,
                "ai2",
                "pw",
                "AI",
                10_000L,
                false,
                "aivai");

        assertEquals("pve", pve.mode());
        assertEquals("aivai", aivai.mode());
    }

    @Test
    void rejectsUnsupportedMatchMode() {
        assertThrows(IllegalArgumentException.class, () -> new AiClientConfig(
                AiClientConfig.DEFAULT_SERVER_URL,
                "ai",
                "pw",
                "AI",
                10_000L,
                false,
                "pvp"));
    }
}
