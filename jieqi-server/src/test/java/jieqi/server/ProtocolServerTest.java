package jieqi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @Test
    void bothReadySendsGameStartWithDifferentColors() {
        StartedGame game = startGame();

        JsonObject redStart = game.red.lastOfType("gameStart");
        JsonObject blackStart = game.black.lastOfType("gameStart");

        assertEquals("u1", redStart.get("redPlayerId").getAsString());
        assertEquals("u2", redStart.get("blackPlayerId").getAsString());
        assertEquals("red", redStart.get("yourColor").getAsString());
        assertEquals("black", blackStart.get("yourColor").getAsString());
        assertNotEquals(redStart.get("yourColor").getAsString(), blackStart.get("yourColor").getAsString());
        assertTrue(redStart.get("firstHand").getAsBoolean());
        assertFalse(blackStart.get("firstHand").getAsBoolean());
        JsonArray initialBoard = redStart.getAsJsonArray("initialBoard");
        assertEquals(32, initialBoard.size());
    }

    @Test
    void illegalMoveReturnsInvalidAndDoesNotSwitchTurn() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "c", 3, true));

        JsonObject rejected = game.red.lastOfType("moveResult");
        assertFalse(rejected.get("valid").getAsBoolean());
        assertTrue(game.black.messagesOfType("moveResult").isEmpty());

        game.red.clear();
        game.server.onMessage(game.red, move("b", 2, "e", 2, false));

        JsonObject accepted = game.red.lastOfType("moveResult");
        assertTrue(accepted.get("valid").getAsBoolean());
        assertTrue(accepted.getAsJsonObject("move").get("isFlip").getAsBoolean());
    }

    @Test
    void nonCurrentPlayerMoveCannotBeLegal() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.black, move("b", 7, "e", 7, true));

        JsonObject rejected = game.black.lastOfType("moveResult");
        assertFalse(rejected.get("valid").getAsBoolean());
        assertTrue(game.red.messagesOfType("moveResult").isEmpty());
    }

    @Test
    void legalMoveReturnsValidAndSwitchesTurn() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));
        JsonObject redMove = game.red.lastOfType("moveResult");
        JsonObject blackSeenMove = game.black.lastOfType("moveResult");
        assertTrue(redMove.get("valid").getAsBoolean());
        assertTrue(blackSeenMove.get("valid").getAsBoolean());

        game.clear();
        game.server.onMessage(game.red, move("h", 2, "f", 2, true));
        assertFalse(game.red.lastOfType("moveResult").get("valid").getAsBoolean());

        game.red.clear();
        game.server.onMessage(game.black, move("b", 7, "e", 7, true));
        assertTrue(game.black.lastOfType("moveResult").get("valid").getAsBoolean());
    }

    @Test
    void badMoveCoordinatesOrMissingFieldsDoNotCrash() {
        StartedGame game = startGame();
        game.clear();

        assertDoesNotThrow(() -> game.server.onMessage(game.red,
                "{\"messageType\":\"move\",\"fromX\":\"z\",\"fromY\":2,\"toX\":\"e\",\"toY\":2,\"isFlip\":true}"));
        assertEquals("error", game.red.lastOfType("error").get("messageType").getAsString());

        game.red.clear();
        assertDoesNotThrow(() -> game.server.onMessage(game.red,
                "{\"messageType\":\"move\",\"fromX\":\"b\",\"fromY\":2,\"toX\":\"e\",\"isFlip\":true}"));
        assertEquals("error", game.red.lastOfType("error").get("messageType").getAsString());
    }

    private static ProtocolServer newServer() {
        Core.ServerConfig config = new Core.ServerConfig();
        config.usersFile = null;
        config.autoRegisterOnLogin = true;
        return new ProtocolServer(config);
    }

    private static StartedGame startGame() {
        ProtocolServer server = newServer();
        FakeChannel red = new FakeChannel("red");
        FakeChannel black = new FakeChannel("black");
        server.onConnected(red);
        server.onConnected(black);
        server.onMessage(red, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");
        server.onMessage(black, "{\"messageType\":\"Login\",\"userId\":\"u2\",\"password\":\"p2\"}");
        red.clear();
        black.clear();
        server.onMessage(red, "{\"messageType\":\"startMatch\"}");
        server.onMessage(black, "{\"messageType\":\"startMatch\"}");
        red.clear();
        black.clear();
        server.onMessage(red, "{\"messageType\":\"Ready\"}");
        server.onMessage(black, "{\"messageType\":\"Ready\"}");
        return new StartedGame(server, red, black);
    }

    private static String move(String fromX, int fromY, String toX, int toY, boolean isFlip) {
        return "{\"messageType\":\"move\",\"fromX\":\"" + fromX + "\",\"fromY\":" + fromY
                + ",\"toX\":\"" + toX + "\",\"toY\":" + toY + ",\"isFlip\":" + isFlip + "}";
    }

    private record StartedGame(ProtocolServer server, FakeChannel red, FakeChannel black) {
        void clear() {
            red.clear();
            black.clear();
        }
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

        List<JsonObject> messagesOfType(String type) {
            List<JsonObject> matches = new ArrayList<>();
            for (String json : outbox) {
                JsonObject object = JsonParser.parseString(json).getAsJsonObject();
                if (type.equals(object.get("messageType").getAsString())) {
                    matches.add(object);
                }
            }
            return matches;
        }

        JsonObject lastOfType(String type) {
            List<JsonObject> matches = messagesOfType(type);
            return matches.get(matches.size() - 1);
        }

        void clear() {
            outbox.clear();
        }
    }
}
