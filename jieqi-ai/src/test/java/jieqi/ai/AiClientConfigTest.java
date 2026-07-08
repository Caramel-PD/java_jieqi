package jieqi.ai;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    }
}
