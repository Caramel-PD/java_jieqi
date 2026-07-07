package jieqi.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolServerTest {
    @Test
    void parsePortDefaultsTo8887() {
        assertEquals(ServerMain.DEFAULT_PORT, ServerMain.parsePort(new String[0], ServerMain.DEFAULT_PORT));
    }

    @Test
    void parsePortUsesFirstArgument() {
        assertEquals(9000, ServerMain.parsePort(new String[]{"9000"}, ServerMain.DEFAULT_PORT));
    }

    @Test
    void parsePortRejectsInvalidPort() {
        assertThrows(IllegalArgumentException.class,
                () -> ServerMain.parsePort(new String[]{"0"}, ServerMain.DEFAULT_PORT));
        assertThrows(IllegalArgumentException.class,
                () -> ServerMain.parsePort(new String[]{"abc"}, ServerMain.DEFAULT_PORT));
    }

    @Test
    void pingReturnsPongWithSameTimestamp() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        server.onMessage(channel, "{\"messageType\":\"ping\",\"timestamp\":1712345678901}");

        assertEquals("{\"messageType\":\"pong\",\"timestamp\":1712345678901}", channel.last());
    }

    @Test
    void messageTypeParsingIsCaseInsensitive() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        server.onMessage(channel, "{\"messageType\":\"PiNg\",\"timestamp\":1712345678901}");

        assertEquals("{\"messageType\":\"pong\",\"timestamp\":1712345678901}", channel.last());
    }

    @Test
    void badJsonReturnsErrorAndDoesNotCrash() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        assertDoesNotThrow(() -> server.onMessage(channel, "not json"));

        JsonObject response = JsonParser.parseString(channel.last()).getAsJsonObject();
        assertEquals("error", response.get("messageType").getAsString());
        assertEquals(ProtocolServer.ERROR_BAD_JSON, response.get("code").getAsInt());
    }

    @Test
    void missingMessageTypeReturnsErrorAndDoesNotCrash() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        assertDoesNotThrow(() -> server.onMessage(channel, "{\"timestamp\":1}"));

        JsonObject response = JsonParser.parseString(channel.last()).getAsJsonObject();
        assertEquals("error", response.get("messageType").getAsString());
        assertEquals(ProtocolServer.ERROR_BAD_JSON, response.get("code").getAsInt());
    }

    @Test
    void unknownMessageTypeIsIgnoredWithoutReply() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        assertDoesNotThrow(() -> server.onMessage(channel, "{\"messageType\":\"unknown\"}"));

        assertTrue(channel.outbox.isEmpty());
    }

    @Test
    void twoLoggedInUsersStartMatchAndReceiveMatchSuccess() {
        ProtocolServer server = newServer();
        FakeChannel c1 = new FakeChannel("c1");
        FakeChannel c2 = new FakeChannel("c2");
        server.onConnected(c1);
        server.onConnected(c2);
        server.onMessage(c1, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");
        server.onMessage(c2, "{\"messageType\":\"login\",\"userId\":\"u2\",\"password\":\"p2\"}");
        c1.clear();
        c2.clear();

        server.onMessage(c1, "{\"messageType\":\"startMatch\"}");
        assertTrue(c1.outbox.isEmpty());
        server.onMessage(c2, "{\"messageType\":\"STARTMATCH\"}");

        JsonObject r1 = JsonParser.parseString(c1.last()).getAsJsonObject();
        JsonObject r2 = JsonParser.parseString(c2.last()).getAsJsonObject();
        assertEquals("matchSuccess", r1.get("messageType").getAsString());
        assertEquals("matchSuccess", r2.get("messageType").getAsString());
        assertEquals(r1.get("roomId").getAsString(), r2.get("roomId").getAsString());
        assertEquals("u2", r1.get("opponentId").getAsString());
        assertEquals("u1", r2.get("opponentId").getAsString());
        assertFalse(r1.get("roomId").getAsString().isBlank());
    }

    private static ProtocolServer newServer() {
        Core.ServerConfig config = new Core.ServerConfig();
        config.usersFile = null;
        config.autoRegisterOnLogin = true;
        return new ProtocolServer(config);
    }

    private static final class FakeChannel implements Core.ClientChannel {
        private final String remote;
        private final List<String> outbox = new ArrayList<>();
        private boolean open = true;

        FakeChannel(String remote) {
            this.remote = remote;
        }

        @Override
        public void send(String json) {
            outbox.add(json);
        }

        @Override
        public void closeConnection() {
            open = false;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public String remote() {
            return remote;
        }

        String last() {
            return outbox.get(outbox.size() - 1);
        }

        void clear() {
            outbox.clear();
        }
    }
}
