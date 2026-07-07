package jieqi.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerMainTest {
    @Test
    void parsePortDefaultsTo8887() {
        assertEquals(8887, ServerMain.parsePort(new String[0]));
    }

    @Test
    void parsePortUsesFirstArgument() {
        assertEquals(9000, ServerMain.parsePort(new String[]{"9000"}));
    }

    @Test
    void parsePortRejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> ServerMain.parsePort(new String[]{"0"}));
        assertThrows(IllegalArgumentException.class, () -> ServerMain.parsePort(new String[]{"abc"}));
    }
}
