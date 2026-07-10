package jieqi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jieqi.common.Color;
import jieqi.rules.BoardText;
import jieqi.rules.RepetitionVerdict;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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
    void emptyServerStatusIsAvailableBeforeLogin() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        server.onMessage(channel, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = channel.lastOfType("serverStatus");
        assertEquals(0, status.get("onlineUsers").getAsInt());
        assertEquals(0, status.get("waitingUsers").getAsInt());
        assertEquals(0, status.getAsJsonArray("rooms").size());
    }

    @Test
    void serverStatusCountsOnlineUsersAfterLogin() {
        ProtocolServer server = newServer();
        FakeChannel c1 = new FakeChannel("c1");
        FakeChannel c2 = new FakeChannel("c2");
        server.onConnected(c1);
        server.onConnected(c2);
        server.onMessage(c1, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");
        server.onMessage(c2, "{\"messageType\":\"Login\",\"userId\":\"u2\",\"password\":\"p2\"}");
        c1.clear();

        server.onMessage(c1, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = c1.lastOfType("serverStatus");
        assertEquals(2, status.get("onlineUsers").getAsInt());
        assertEquals(0, status.get("waitingUsers").getAsInt());
        assertEquals(0, status.getAsJsonArray("rooms").size());
    }

    @Test
    void serverStatusCountsWaitingUserAfterStartMatch() {
        ProtocolServer server = newServer();
        FakeChannel c1 = new FakeChannel("c1");
        server.onConnected(c1);
        server.onMessage(c1, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");
        c1.clear();

        server.onMessage(c1, "{\"messageType\":\"startMatch\"}");
        server.onMessage(c1, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = c1.lastOfType("serverStatus");
        assertEquals(1, status.get("onlineUsers").getAsInt());
        assertEquals(1, status.get("waitingUsers").getAsInt());
        assertEquals(0, status.getAsJsonArray("rooms").size());
    }

    @Test
    void cancelMatchRemovesWaitingUser() {
        ProtocolServer server = newServer();
        FakeChannel c1 = new FakeChannel("c1");
        server.onConnected(c1);
        server.onMessage(c1, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");
        c1.clear();

        server.onMessage(c1, "{\"messageType\":\"startMatch\"}");
        server.onMessage(c1, "{\"messageType\":\"cancelMatch\"}");
        server.onMessage(c1, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = c1.lastOfType("serverStatus");
        assertEquals(1, status.get("onlineUsers").getAsInt());
        assertEquals(0, status.get("waitingUsers").getAsInt());
        assertEquals(0, status.getAsJsonArray("rooms").size());
    }

    @Test
    void userCanStartMatchAgainAfterCancelMatch() {
        ProtocolServer server = newServer();
        FakeChannel c1 = new FakeChannel("c1");
        FakeChannel c2 = new FakeChannel("c2");
        server.onConnected(c1);
        server.onConnected(c2);
        server.onMessage(c1, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");
        server.onMessage(c2, "{\"messageType\":\"Login\",\"userId\":\"u2\",\"password\":\"p2\"}");
        c1.clear();
        c2.clear();

        server.onMessage(c1, "{\"messageType\":\"startMatch\"}");
        server.onMessage(c1, "{\"messageType\":\"cancelMatch\"}");
        server.onMessage(c1, "{\"messageType\":\"startMatch\"}");
        server.onMessage(c2, "{\"messageType\":\"startMatch\"}");

        assertEquals("matchSuccess", c1.lastOfType("matchSuccess").get("messageType").getAsString());
        assertEquals("matchSuccess", c2.lastOfType("matchSuccess").get("messageType").getAsString());
    }

    @Test
    void cancelMatchOutsideMatchingDoesNotAffectRoom() {
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

        server.onMessage(red, "{\"messageType\":\"cancelMatch\"}");
        server.onMessage(red, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = red.lastOfType("serverStatus");
        assertEquals(0, status.get("waitingUsers").getAsInt());
        JsonArray rooms = status.getAsJsonArray("rooms");
        assertEquals(1, rooms.size());
        JsonObject room = rooms.get(0).getAsJsonObject();
        assertEquals("room_1", room.get("roomId").getAsString());
        assertEquals("u1", room.get("redPlayerId").getAsString());
        assertEquals("u2", room.get("blackPlayerId").getAsString());
    }

    @Test
    void cancelMatchBeforeLoginReturnsAuthErrorAndDoesNotCrash() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        assertDoesNotThrow(() -> server.onMessage(channel, "{\"messageType\":\"cancelMatch\"}"));

        JsonObject error = channel.lastOfType("error");
        assertEquals(ProtocolServer.ERROR_AUTH, error.get("code").getAsInt());
        assertEquals("login required", error.get("message").getAsString());
    }

    @Test
    void serverStatusListsRoomAfterMatch() {
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
        server.onMessage(red, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = red.lastOfType("serverStatus");
        assertEquals(2, status.get("onlineUsers").getAsInt());
        assertEquals(0, status.get("waitingUsers").getAsInt());
        JsonArray rooms = status.getAsJsonArray("rooms");
        assertEquals(1, rooms.size());
        JsonObject room = rooms.get(0).getAsJsonObject();
        assertEquals("room_1", room.get("roomId").getAsString());
        assertEquals("u1", room.get("redPlayerId").getAsString());
        assertEquals("u2", room.get("blackPlayerId").getAsString());
        assertFalse(room.get("started").getAsBoolean());
        assertFalse(room.get("finished").getAsBoolean());
        assertEquals("red", room.get("currentTurn").getAsString());
    }

    @Test
    void serverStatusShowsStartedAfterBothReady() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = game.red.lastOfType("serverStatus");
        JsonArray rooms = status.getAsJsonArray("rooms");
        assertEquals(1, rooms.size());
        JsonObject room = rooms.get(0).getAsJsonObject();
        assertEquals("room_1", room.get("roomId").getAsString());
        assertTrue(room.get("started").getAsBoolean());
        assertFalse(room.get("finished").getAsBoolean());
        assertEquals("red", room.get("currentTurn").getAsString());
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
    void firstHandWindowZeroStartsImmediatelyAfterBothReady() {
        StartedGame game = startGame(65_000, null, 0, null, null);

        assertFalse(game.red.messagesOfType("gameStart").isEmpty());
        assertFalse(game.black.messagesOfType("gameStart").isEmpty());
    }

    @Test
    void requestFirstHandInsideWindowAffectsGameStart() throws Exception {
        StartedGame game = matchedGame(65_000, null, 80);

        game.server.onMessage(game.red, "{\"messageType\":\"Ready\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"Ready\"}");
        assertTrue(game.red.messagesOfType("gameStart").isEmpty());
        assertTrue(game.black.messagesOfType("gameStart").isEmpty());

        game.server.onMessage(game.black, requestFirstHand(true));
        waitUntil(() -> !game.red.messagesOfType("gameStart").isEmpty()
                && !game.black.messagesOfType("gameStart").isEmpty());

        JsonObject defaultRedStart = game.red.lastOfType("gameStart");
        JsonObject defaultBlackStart = game.black.lastOfType("gameStart");
        assertEquals("u2", defaultRedStart.get("redPlayerId").getAsString());
        assertEquals("u1", defaultRedStart.get("blackPlayerId").getAsString());
        assertEquals("black", defaultRedStart.get("yourColor").getAsString());
        assertEquals("red", defaultBlackStart.get("yourColor").getAsString());
    }

    @Test
    void firstHandWindowEndsAndStartsGameAutomatically() throws Exception {
        StartedGame game = matchedGame(65_000, null, 60);

        game.server.onMessage(game.red, "{\"messageType\":\"Ready\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"Ready\"}");

        waitUntil(() -> !game.red.messagesOfType("gameStart").isEmpty()
                && !game.black.messagesOfType("gameStart").isEmpty());

        JsonObject redStart = game.red.lastOfType("gameStart");
        assertEquals("u1", redStart.get("redPlayerId").getAsString());
        assertEquals("u2", redStart.get("blackPlayerId").getAsString());
    }

    @Test
    void moveDuringFirstHandWindowIsRejectedAsGameNotStarted() {
        StartedGame game = matchedGame(65_000, null, 500);

        game.server.onMessage(game.red, "{\"messageType\":\"Ready\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"Ready\"}");
        game.clear();
        game.server.onMessage(game.red, move("b", 2, "e", 2, true));

        JsonObject rejected = game.red.lastOfType("moveResult");
        assertFalse(rejected.get("valid").getAsBoolean());
        JsonObject error = game.red.lastOfType("error");
        assertEquals(ProtocolServer.ERROR_ILLEGAL_MOVE, error.get("code").getAsInt());
        assertTrue(game.black.messagesOfType("moveResult").isEmpty());
    }

    @Test
    void autoReadyAfterMsDefaultZeroDoesNotStartWithoutReady() throws Exception {
        StartedGame game = matchedGame(65_000, null, 0);

        Thread.sleep(90);

        assertTrue(game.red.messagesOfType("gameStart").isEmpty());
        assertTrue(game.black.messagesOfType("gameStart").isEmpty());
    }

    @Test
    void autoReadyStartsGameWhenBothPlayersDoNotSendReady() throws Exception {
        StartedGame game = matchedGame(65_000, null, 0, 50);

        waitUntil(() -> !game.red.messagesOfType("gameStart").isEmpty()
                && !game.black.messagesOfType("gameStart").isEmpty());

        JsonObject redStart = game.red.lastOfType("gameStart");
        assertEquals("u1", redStart.get("redPlayerId").getAsString());
        assertEquals("u2", redStart.get("blackPlayerId").getAsString());
    }

    @Test
    void autoReadyCompletesMissingReadyWhenOnePlayerIsReady() throws Exception {
        StartedGame game = matchedGame(65_000, null, 0, 50);

        game.server.onMessage(game.red, "{\"messageType\":\"Ready\"}");
        assertTrue(game.red.messagesOfType("gameStart").isEmpty());
        assertTrue(game.black.messagesOfType("gameStart").isEmpty());

        waitUntil(() -> !game.red.messagesOfType("gameStart").isEmpty()
                && !game.black.messagesOfType("gameStart").isEmpty());

        assertFalse(game.red.messagesOfType("roomInfo").isEmpty());
        assertFalse(game.black.messagesOfType("roomInfo").isEmpty());
    }

    @Test
    void autoReadyStillWaitsForFirstHandWindowBeforeGameStart() throws Exception {
        StartedGame game = matchedGame(65_000, null, 150, 40);

        Thread.sleep(90);
        assertTrue(game.red.messagesOfType("roomInfo").size() >= 1);
        assertTrue(game.black.messagesOfType("roomInfo").size() >= 1);
        assertTrue(game.red.messagesOfType("gameStart").isEmpty());
        assertTrue(game.black.messagesOfType("gameStart").isEmpty());

        waitUntil(() -> !game.red.messagesOfType("gameStart").isEmpty()
                && !game.black.messagesOfType("gameStart").isEmpty());
    }

    @Test
    void autoReadyDoesNotStartAfterDisconnectBeforeGameStart() throws Exception {
        StartedGame game = matchedGame(65_000, null, 0, 50);

        game.red.closeConnection();
        game.server.onClosed(game.red);
        Thread.sleep(120);

        assertTrue(game.red.messagesOfType("gameStart").isEmpty());
        assertTrue(game.black.messagesOfType("gameStart").isEmpty());
        game.server.onMessage(game.black, "{\"messageType\":\"serverStatus\"}");
        JsonObject room = game.black.lastOfType("serverStatus").getAsJsonArray("rooms")
                .get(0).getAsJsonObject();
        assertFalse(room.get("started").getAsBoolean());
        assertTrue(room.get("finished").getAsBoolean());
    }

    @Test
    void envLoadsAutoReadyAfterMs() {
        Core.ServerConfig config = Core.ServerConfig.fromEnv(Map.of("JIEQI_AUTO_READY_AFTER_MS", "75"));

        assertEquals(75, config.autoReadyAfterMs);
    }

    @Test
    void fromEnvDefaultsAreUsable() {
        Core.ServerConfig config = Core.ServerConfig.fromEnv(Map.of());

        assertEquals(8887, config.port);
        assertEquals(Path.of(".", "records"), config.recordsDir);
        assertEquals(Path.of(".", "users.json"), config.usersFile);
        assertEquals("virtual", config.initialBoardMode);
    }

    @Test
    void legalEnvValuesOverrideServerConfigFields() {
        Map<String, String> env = new HashMap<>();
        env.put("JIEQI_HOME", "runtime-home");
        env.put("JIEQI_PORT", "8899");
        env.put("JIEQI_TURN_TIMEOUT_MS", "1200");
        env.put("JIEQI_FIRSTHAND_WINDOW_MS", "300");
        env.put("JIEQI_AUTO_READY_AFTER_MS", "75");
        env.put("JIEQI_AUTO_REGISTER", "false");
        env.put("JIEQI_REPETITION_LIMIT", "8");
        env.put("JIEQI_REPETITION_MIN_REPEATS", "4");
        env.put("JIEQI_NO_CAPTURE_LIMIT_HALF_MOVES", "16");
        env.put("JIEQI_INITIAL_BOARD_MODE", "omit");
        env.put("JIEQI_USERS_FILE", "custom-users.json");

        Core.ServerConfig config = Core.ServerConfig.fromEnv(env);

        assertEquals(8899, config.port);
        assertEquals(1200, config.turnTimeoutMs);
        assertEquals(300, config.firstHandWindowMs);
        assertEquals(75, config.autoReadyAfterMs);
        assertFalse(config.autoRegisterOnLogin);
        assertEquals(8, config.repetitionLimit);
        assertEquals(4, config.repetitionMinRepeats);
        assertEquals(16, config.noCaptureLimitHalfMoves);
        assertEquals("omit", config.initialBoardMode);
        assertEquals(Path.of("runtime-home", "records"), config.recordsDir);
        assertEquals(Path.of("custom-users.json"), config.usersFile);
    }

    @Test
    void invalidNumericEnvValuesFallbackToDefaults() {
        Core.ServerConfig defaults = new Core.ServerConfig();
        Core.ServerConfig config = Core.ServerConfig.fromEnv(Map.of(
                "JIEQI_PORT", "bad",
                "JIEQI_REPETITION_LIMIT", "bad",
                "JIEQI_REPETITION_MIN_REPEATS", "bad",
                "JIEQI_NO_CAPTURE_LIMIT_HALF_MOVES", "bad"
        ));

        assertEquals(defaults.port, config.port);
        assertEquals(defaults.repetitionLimit, config.repetitionLimit);
        assertEquals(defaults.repetitionMinRepeats, config.repetitionMinRepeats);
        assertEquals(defaults.noCaptureLimitHalfMoves, config.noCaptureLimitHalfMoves);
    }

    @Test
    void recordsDirEnvOverridesRecordsDir() {
        Core.ServerConfig config = Core.ServerConfig.fromEnv(Map.of(
                "JIEQI_HOME", "runtime-home",
                "JIEQI_RECORDS_DIR", "custom-records"
        ));

        assertEquals(Path.of("custom-records"), config.recordsDir);
        assertEquals(Path.of("runtime-home", "users.json"), config.usersFile);
    }

    @Test
    void initialBoardModeOmitCanBeLoadedFromEnv() {
        Core.ServerConfig config = Core.ServerConfig.fromEnv(Map.of("JIEQI_INITIAL_BOARD_MODE", "omit"));

        assertEquals("omit", config.initialBoardMode);
    }

    @Test
    void blackRequestFirstHandAndRedDoesNotMakesBlackBecomeRed() {
        StartedGame game = startGame(false, true);

        JsonObject defaultRedStart = game.red.lastOfType("gameStart");
        JsonObject defaultBlackStart = game.black.lastOfType("gameStart");

        assertEquals("u2", defaultRedStart.get("redPlayerId").getAsString());
        assertEquals("u1", defaultRedStart.get("blackPlayerId").getAsString());
        assertEquals("black", defaultRedStart.get("yourColor").getAsString());
        assertFalse(defaultRedStart.get("firstHand").getAsBoolean());
        assertEquals("red", defaultBlackStart.get("yourColor").getAsString());
        assertTrue(defaultBlackStart.get("firstHand").getAsBoolean());
    }

    @Test
    void bothRequestFirstHandKeepsDefaultRed() {
        StartedGame game = startGame(true, true);

        JsonObject redStart = game.red.lastOfType("gameStart");
        JsonObject blackStart = game.black.lastOfType("gameStart");

        assertEquals("u1", redStart.get("redPlayerId").getAsString());
        assertEquals("u2", redStart.get("blackPlayerId").getAsString());
        assertEquals("red", redStart.get("yourColor").getAsString());
        assertTrue(redStart.get("firstHand").getAsBoolean());
        assertEquals("black", blackStart.get("yourColor").getAsString());
        assertFalse(blackStart.get("firstHand").getAsBoolean());
    }

    @Test
    void bothDeclineFirstHandKeepsDefaultRed() {
        StartedGame game = startGame(false, false);

        JsonObject redStart = game.red.lastOfType("gameStart");
        JsonObject blackStart = game.black.lastOfType("gameStart");

        assertEquals("u1", redStart.get("redPlayerId").getAsString());
        assertEquals("u2", redStart.get("blackPlayerId").getAsString());
        assertEquals("red", redStart.get("yourColor").getAsString());
        assertTrue(redStart.get("firstHand").getAsBoolean());
        assertEquals("black", blackStart.get("yourColor").getAsString());
        assertFalse(blackStart.get("firstHand").getAsBoolean());
    }

    @Test
    void requestFirstHandAfterGameStartIsIgnored() {
        StartedGame game = startGame();
        JsonObject originalRedStart = game.red.lastOfType("gameStart");
        JsonObject originalBlackStart = game.black.lastOfType("gameStart");
        game.clear();

        game.server.onMessage(game.black, requestFirstHand(true));
        game.server.onMessage(game.red, "{\"messageType\":\"serverStatus\"}");

        JsonObject status = game.red.lastOfType("serverStatus");
        JsonObject room = status.getAsJsonArray("rooms").get(0).getAsJsonObject();
        assertEquals(originalRedStart.get("redPlayerId").getAsString(), room.get("redPlayerId").getAsString());
        assertEquals(originalRedStart.get("blackPlayerId").getAsString(), room.get("blackPlayerId").getAsString());
        assertEquals("red", originalRedStart.get("yourColor").getAsString());
        assertEquals("black", originalBlackStart.get("yourColor").getAsString());
    }

    @Test
    void resignWinnerColorAndWinnerIdRemainCorrectAfterFirstHandSwap() {
        StartedGame game = startGame(false, true);
        game.clear();

        game.server.onMessage(game.black, "{\"messageType\":\"Resign\"}");

        JsonObject gameOver = game.red.lastOfType("gameOver");
        assertEquals("u1", gameOver.get("winnerId").getAsString());
        assertEquals("black", gameOver.get("winner").getAsString());
        assertEquals("resign", gameOver.get("reason").getAsString());
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
    void hiddenCapturedPieceIsVisibleOnlyToCapturingPlayer() {
        StartedGame game = startGame();
        setRoomBoard(game, "4k4/9/9/x8/R3P4/9/9/9/9/4K4 r");
        game.clear();

        game.server.onMessage(game.red, move("a", 5, "a", 6, false));

        JsonObject redMove = game.red.lastOfType("moveResult");
        JsonObject blackMove = game.black.lastOfType("moveResult");
        assertTrue(redMove.get("valid").getAsBoolean());
        assertTrue(blackMove.get("valid").getAsBoolean());
        assertTrue(redMove.has("capturedPiece"));
        assertTrue(blackMove.has("capturedPiece"));
        assertNotEquals("NULL", redMove.get("capturedPiece").getAsString());
        assertEquals("NULL", blackMove.get("capturedPiece").getAsString());
    }

    @Test
    void revealedCapturedPieceIsVisibleToBothPlayers() {
        StartedGame game = startGame();
        setRoomBoard(game, "4k4/9/9/r8/R3P4/9/9/9/9/4K4 r");
        game.clear();

        game.server.onMessage(game.red, move("a", 5, "a", 6, false));

        JsonObject redMove = game.red.lastOfType("moveResult");
        JsonObject blackMove = game.black.lastOfType("moveResult");
        assertTrue(redMove.get("valid").getAsBoolean());
        assertTrue(blackMove.get("valid").getAsBoolean());
        assertEquals("rook", redMove.get("capturedPiece").getAsString());
        assertEquals("rook", blackMove.get("capturedPiece").getAsString());
    }

    @Test
    void nonCaptureMoveResultOmitsCapturedPiece() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));

        JsonObject redMove = game.red.lastOfType("moveResult");
        JsonObject blackMove = game.black.lastOfType("moveResult");
        assertTrue(redMove.get("valid").getAsBoolean());
        assertTrue(blackMove.get("valid").getAsBoolean());
        assertFalse(redMove.has("capturedPiece"));
        assertFalse(blackMove.has("capturedPiece"));
    }

    @Test
    void recorderCapturedPieceDoesNotChangeDifferentiatedBroadcast(@TempDir Path recordsDir) throws Exception {
        StartedGame game = startGame(65_000, recordsDir);
        setRoomBoard(game, "4k4/9/9/x8/R3P4/9/9/9/9/4K4 r");
        game.clear();

        game.server.onMessage(game.red, move("a", 5, "a", 6, false));

        JsonObject redMove = game.red.lastOfType("moveResult");
        JsonObject blackMove = game.black.lastOfType("moveResult");
        String recordedCapture = redMove.get("capturedPiece").getAsString();
        assertNotEquals("NULL", recordedCapture);
        assertEquals("NULL", blackMove.get("capturedPiece").getAsString());

        game.server.onMessage(game.black, "{\"messageType\":\"Resign\"}");

        JsonObject recordMove = readRecord(recordsDir, "room_1").getAsJsonArray("moves")
                .get(0).getAsJsonObject();
        assertEquals(recordedCapture, recordMove.get("capturedPiece").getAsString());
        assertEquals("NULL", blackMove.get("capturedPiece").getAsString());
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

    @Test
    void redTimeoutMakesBlackWinner() throws Exception {
        StartedGame game = startGame(80);
        game.clear();

        waitUntil(() -> !game.red.messagesOfType("timeout").isEmpty()
                && !game.black.messagesOfType("timeout").isEmpty());

        JsonObject timeout = game.red.lastOfType("timeout");
        assertEquals("u1", timeout.get("loserId").getAsString());
        assertEquals("u2", timeout.get("winnerId").getAsString());
        assertEquals("timeout", timeout.get("reason").getAsString());

        JsonObject gameOver = game.black.lastOfType("gameOver");
        assertEquals("u2", gameOver.get("winnerId").getAsString());
        assertEquals("timeout", gameOver.get("reason").getAsString());
    }

    @Test
    void legalMoveSwitchesTimerToBlack() throws Exception {
        StartedGame game = startGame(120);
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));
        assertTrue(game.red.lastOfType("moveResult").get("valid").getAsBoolean());

        waitUntil(() -> !game.red.messagesOfType("timeout").isEmpty()
                && !game.black.messagesOfType("timeout").isEmpty());

        JsonObject timeout = game.red.lastOfType("timeout");
        assertEquals("u2", timeout.get("loserId").getAsString());
        assertEquals("u1", timeout.get("winnerId").getAsString());
    }

    @Test
    void noCaptureLimitOneDrawsAfterFirstLegalNonCaptureMove() {
        StartedGame game = startGameWithNoCaptureLimit(1);
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));

        JsonObject redGameOver = game.red.lastOfType("gameOver");
        JsonObject blackGameOver = game.black.lastOfType("gameOver");
        assertEquals("draw", redGameOver.get("winner").getAsString());
        assertEquals("noCapture", redGameOver.get("reason").getAsString());
        assertFalse(redGameOver.has("winnerId"));
        assertEquals("draw", blackGameOver.get("winner").getAsString());
        assertEquals("noCapture", blackGameOver.get("reason").getAsString());
        assertFalse(blackGameOver.has("winnerId"));
    }

    @Test
    void moveAfterDrawNoCaptureIsIgnored() {
        StartedGame game = startGameWithNoCaptureLimit(1);
        game.clear();
        game.server.onMessage(game.red, move("b", 2, "e", 2, true));
        int redMoveResults = game.red.messagesOfType("moveResult").size();
        int blackMoveResults = game.black.messagesOfType("moveResult").size();
        int redGameOvers = game.red.messagesOfType("gameOver").size();
        int blackGameOvers = game.black.messagesOfType("gameOver").size();

        game.server.onMessage(game.black, move("b", 7, "e", 7, true));

        assertEquals(redMoveResults, game.red.messagesOfType("moveResult").size());
        assertEquals(blackMoveResults, game.black.messagesOfType("moveResult").size());
        assertEquals(redGameOvers, game.red.messagesOfType("gameOver").size());
        assertEquals(blackGameOvers, game.black.messagesOfType("gameOver").size());
    }

    @Test
    void repetitionLossMapsMoverToLoserAndOpponentToWinner() {
        ProtocolServer.RepetitionOutcome outcome =
                ProtocolServer.repetitionOutcome(RepetitionVerdict.REPETITION_LOSS, Color.RED);

        assertFalse(outcome.draw());
        assertEquals(Color.BLACK, outcome.winnerColor());
        assertEquals("repetition", outcome.reason());
    }

    @Test
    void repetitionDrawMapsToDrawWithoutWinnerId() {
        ProtocolServer.RepetitionOutcome outcome =
                ProtocolServer.repetitionOutcome(RepetitionVerdict.REPETITION_DRAW, Color.BLACK);

        assertTrue(outcome.draw());
        assertEquals("repetition", outcome.reason());
        JsonObject gameOver = JsonParser.parseString(Messages.gameOver("draw", outcome.reason(), null))
                .getAsJsonObject();
        assertEquals("draw", gameOver.get("winner").getAsString());
        assertEquals("repetition", gameOver.get("reason").getAsString());
        assertFalse(gameOver.has("winnerId"));
    }

    @Test
    void requestDrawSendsDrawOfferToOpponent() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");

        assertTrue(game.red.messagesOfType("drawOffer").isEmpty());
        JsonObject offer = game.black.lastOfType("drawOffer");
        assertEquals("u1", offer.get("requesterId").getAsString());
    }

    @Test
    void rejectedDrawNotifiesRequester() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"drawResponse\",\"accept\":false}");

        JsonObject result = game.red.lastOfType("drawResponseResult");
        assertFalse(result.get("accepted").getAsBoolean());
        assertEquals("u2", result.get("responderId").getAsString());
        assertTrue(game.black.messagesOfType("drawResponseResult").isEmpty());
    }

    @Test
    void acceptedDrawStillUsesGameOverDrawAgreed() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"drawResponse\",\"accept\":true}");

        JsonObject gameOver = game.red.lastOfType("gameOver");
        assertEquals("draw", gameOver.get("winner").getAsString());
        assertEquals("draw_agreed", gameOver.get("reason").getAsString());
        assertFalse(gameOver.has("winnerId"));
        assertTrue(game.red.messagesOfType("drawResponseResult").isEmpty());
    }

    @Test
    void drawOfferDoesNotChangeCurrentTurn() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.red, "{\"messageType\":\"serverStatus\"}");

        JsonObject room = game.red.lastOfType("serverStatus").getAsJsonArray("rooms").get(0).getAsJsonObject();
        assertEquals("red", room.get("currentTurn").getAsString());
        assertTrue(game.red.messagesOfType("moveResult").isEmpty());
        assertTrue(game.black.messagesOfType("moveResult").isEmpty());
    }

    @Test
    void drawResponseResultDoesNotResetTimerOrMoveState() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"drawResponse\",\"accept\":false}");
        game.server.onMessage(game.red, "{\"messageType\":\"serverStatus\"}");
        JsonObject room = game.red.lastOfType("serverStatus").getAsJsonArray("rooms").get(0).getAsJsonObject();
        assertEquals("red", room.get("currentTurn").getAsString());

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));

        JsonObject moveResult = game.red.lastOfType("moveResult");
        assertTrue(moveResult.get("valid").getAsBoolean());
        assertTrue(game.red.messagesOfType("gameOver").isEmpty());
        assertTrue(game.black.messagesOfType("gameOver").isEmpty());
    }

    @Test
    void twoMatchesCreateTwoIndependentRooms() {
        TwoStartedGames games = startTwoGames();

        assertEquals(1, games.room1().red().messagesOfType("gameStart").size());
        assertEquals(1, games.room1().black().messagesOfType("gameStart").size());
        assertEquals(1, games.room2().red().messagesOfType("gameStart").size());
        assertEquals(1, games.room2().black().messagesOfType("gameStart").size());
        assertEquals("u1", games.room1().red().lastOfType("gameStart").get("redPlayerId").getAsString());
        assertEquals("u2", games.room1().black().lastOfType("gameStart").get("blackPlayerId").getAsString());
        assertEquals("u3", games.room2().red().lastOfType("gameStart").get("redPlayerId").getAsString());
        assertEquals("u4", games.room2().black().lastOfType("gameStart").get("blackPlayerId").getAsString());
    }

    @Test
    void moveResultIsOnlyBroadcastInsideSameRoom() {
        TwoStartedGames games = startTwoGames();
        games.clear();

        games.server().onMessage(games.room1().red(), move("b", 2, "e", 2, true));

        assertEquals(1, games.room1().red().messagesOfType("moveResult").size());
        assertEquals(1, games.room1().black().messagesOfType("moveResult").size());
        assertTrue(games.room2().red().messagesOfType("moveResult").isEmpty());
        assertTrue(games.room2().black().messagesOfType("moveResult").isEmpty());
        assertTrue(games.room1().red().lastOfType("moveResult").get("valid").getAsBoolean());
    }

    @Test
    void gameOverInOneRoomDoesNotFinishOtherRoom() {
        TwoStartedGames games = startTwoGames();
        games.clear();

        games.server().onMessage(games.room1().red(), "{\"messageType\":\"Resign\"}");

        JsonObject room1GameOver = games.room1().black().lastOfType("gameOver");
        assertEquals("u2", room1GameOver.get("winnerId").getAsString());
        assertEquals("resign", room1GameOver.get("reason").getAsString());
        assertTrue(games.room2().red().messagesOfType("gameOver").isEmpty());
        assertTrue(games.room2().black().messagesOfType("gameOver").isEmpty());

        games.server().onMessage(games.room2().red(), move("b", 2, "e", 2, true));

        assertTrue(games.room2().red().lastOfType("moveResult").get("valid").getAsBoolean());
        assertTrue(games.room2().black().lastOfType("moveResult").get("valid").getAsBoolean());
    }

    @Test
    void drawOfferDoesNotLeakToAnotherRoom() {
        TwoStartedGames games = startTwoGames();
        games.clear();

        games.server().onMessage(games.room1().red(), "{\"messageType\":\"requestDraw\"}");

        assertTrue(games.room1().red().messagesOfType("drawOffer").isEmpty());
        JsonObject offer = games.room1().black().lastOfType("drawOffer");
        assertEquals("u1", offer.get("requesterId").getAsString());
        assertTrue(games.room2().red().messagesOfType("drawOffer").isEmpty());
        assertTrue(games.room2().black().messagesOfType("drawOffer").isEmpty());
    }

    @Test
    void serverStatusListsTwoRooms() {
        TwoStartedGames games = startTwoGames();
        games.clear();

        games.server().onMessage(games.room1().red(), "{\"messageType\":\"serverStatus\"}");

        JsonArray rooms = games.room1().red().lastOfType("serverStatus").getAsJsonArray("rooms");
        Map<String, JsonObject> byRoomId = roomsById(rooms);
        assertEquals(2, byRoomId.size());
        assertRoomStatus(byRoomId.get("room_1"), "room_1", "u1", "u2", true, false, "red");
        assertRoomStatus(byRoomId.get("room_2"), "room_2", "u3", "u4", true, false, "red");
    }

    @Test
    void requestDrawDoesNotChangeCurrentTurn() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.red, "{\"messageType\":\"serverStatus\"}");

        JsonObject room = game.red.lastOfType("serverStatus").getAsJsonArray("rooms").get(0).getAsJsonObject();
        assertEquals("red", room.get("currentTurn").getAsString());
        assertTrue(game.red.messagesOfType("moveResult").isEmpty());
        assertTrue(game.black.messagesOfType("moveResult").isEmpty());
    }

    @Test
    void rejectedDrawKeepsGamePlayableForCurrentPlayer() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"drawResponse\",\"accept\":false}");
        game.server.onMessage(game.red, move("b", 2, "e", 2, true));

        JsonObject moveResult = game.red.lastOfType("moveResult");
        assertTrue(moveResult.get("valid").getAsBoolean());
        assertTrue(game.red.messagesOfType("gameOver").isEmpty());
        assertTrue(game.black.messagesOfType("gameOver").isEmpty());
    }

    @Test
    void acceptedDrawSendsDrawAgreedGameOverToBothPlayers() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"drawResponse\",\"accept\":true}");

        JsonObject redGameOver = game.red.lastOfType("gameOver");
        JsonObject blackGameOver = game.black.lastOfType("gameOver");
        assertEquals("draw", redGameOver.get("winner").getAsString());
        assertEquals("draw_agreed", redGameOver.get("reason").getAsString());
        assertFalse(redGameOver.has("winnerId"));
        assertEquals("draw", blackGameOver.get("winner").getAsString());
        assertEquals("draw_agreed", blackGameOver.get("reason").getAsString());
        assertFalse(blackGameOver.has("winnerId"));
    }

    @Test
    void drawAgreedGameOverIsIdempotentAfterMoveResignAndDisconnect() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"requestDraw\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"drawResponse\",\"accept\":true}");
        int redGameOverCount = game.red.messagesOfType("gameOver").size();
        int blackGameOverCount = game.black.messagesOfType("gameOver").size();

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));
        game.server.onMessage(game.red, "{\"messageType\":\"Resign\"}");
        game.red.closeConnection();
        game.server.onClosed(game.red);

        assertEquals(redGameOverCount, game.red.messagesOfType("gameOver").size());
        assertEquals(blackGameOverCount, game.black.messagesOfType("gameOver").size());
        assertEquals("draw_agreed", game.black.lastOfType("gameOver").get("reason").getAsString());
        assertTrue(game.red.messagesOfType("moveResult").isEmpty());
    }

    @Test
    void drawMessagesOutsidePlayingRoomDoNotCrash() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        assertDoesNotThrow(() -> server.onMessage(channel, "{\"messageType\":\"requestDraw\"}"));
        assertDoesNotThrow(() -> server.onMessage(channel, "{\"messageType\":\"drawResponse\",\"accept\":true}"));

        assertTrue(channel.outbox.isEmpty());
    }

    @Test
    void resignSendsGameOverToBothPlayers() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"Resign\"}");

        JsonObject redGameOver = game.red.lastOfType("gameOver");
        JsonObject blackGameOver = game.black.lastOfType("gameOver");
        assertEquals("u2", redGameOver.get("winnerId").getAsString());
        assertEquals("resign", redGameOver.get("reason").getAsString());
        assertEquals("u2", blackGameOver.get("winnerId").getAsString());
        assertEquals("resign", blackGameOver.get("reason").getAsString());
    }

    @Test
    void disconnectSendsGameOverToOnlineOpponent() {
        StartedGame game = startGame();
        game.clear();

        game.red.closeConnection();
        game.server.onClosed(game.red);

        assertTrue(game.red.messagesOfType("gameOver").isEmpty());
        JsonObject gameOver = game.black.lastOfType("gameOver");
        assertEquals("u2", gameOver.get("winnerId").getAsString());
        assertEquals("disconnect", gameOver.get("reason").getAsString());
    }

    @Test
    void moveAfterGameOverDoesNotChangeGameState() {
        StartedGame game = startGame();
        game.clear();
        game.server.onMessage(game.red, "{\"messageType\":\"Resign\"}");
        int redGameOverCount = game.red.messagesOfType("gameOver").size();
        int blackGameOverCount = game.black.messagesOfType("gameOver").size();

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));

        assertEquals(redGameOverCount, game.red.messagesOfType("gameOver").size());
        assertEquals(blackGameOverCount, game.black.messagesOfType("gameOver").size());
        assertTrue(game.red.messagesOfType("moveResult").isEmpty());
        assertTrue(game.black.messagesOfType("moveResult").isEmpty());
        assertEquals("u2", game.red.lastOfType("gameOver").get("winnerId").getAsString());
        assertEquals("resign", game.red.lastOfType("gameOver").get("reason").getAsString());
    }

    @Test
    void gameOverIsSentOnlyOnce() throws Exception {
        StartedGame game = startGame(80);
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"Resign\"}");
        game.server.onMessage(game.red, "{\"messageType\":\"Resign\"}");
        game.red.closeConnection();
        game.server.onClosed(game.red);

        Thread.sleep(180);

        assertEquals(1, game.red.messagesOfType("gameOver").size());
        assertEquals(1, game.black.messagesOfType("gameOver").size());
        assertEquals("resign", game.black.lastOfType("gameOver").get("reason").getAsString());
    }

    @Test
    void resignWritesParseableGameRecordWithGameOverFields(@TempDir Path recordsDir) throws Exception {
        StartedGame game = startGame(65_000, recordsDir);
        game.clear();

        game.server.onMessage(game.red, "{\"messageType\":\"Resign\"}");

        JsonObject record = readRecord(recordsDir, "room_1");
        assertEquals("room_1", record.get("roomId").getAsString());
        assertEquals("u1", record.get("redPlayerId").getAsString());
        assertEquals("u2", record.get("blackPlayerId").getAsString());
        assertTrue(record.get("startTime").getAsLong() > 0);
        assertTrue(record.get("endTime").getAsLong() >= record.get("startTime").getAsLong());
        assertEquals("black", record.get("winner").getAsString());
        assertEquals("u2", record.get("winnerId").getAsString());
        assertEquals("resign", record.get("reason").getAsString());
        assertTrue(record.get("moves").isJsonArray());
    }

    @Test
    void registerSuccess() {
        ProtocolServer server = newServer(false);  // 关闭自动注册，单独测 register
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        server.onMessage(channel,
                "{\"messageType\":\"register\",\"userId\":\"newUser\",\"password\":\"pw\",\"nickname\":\"新用户\"}");

        JsonObject result = channel.lastOfType("loginResult");
        assertTrue(result.get("success").getAsBoolean());
        assertEquals("newUser", result.get("userId").getAsString());
        assertEquals("ok", result.get("message").getAsString());
    }

    @Test
    void duplicateLoginRejected() {
        ProtocolServer server = newServer();
        FakeChannel c1 = new FakeChannel("c1");
        FakeChannel c2 = new FakeChannel("c2");
        server.onConnected(c1);
        server.onConnected(c2);
        server.onMessage(c1, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");
        c1.clear();

        server.onMessage(c2, "{\"messageType\":\"Login\",\"userId\":\"u1\",\"password\":\"p1\"}");

        JsonObject error = c2.lastOfType("error");
        assertEquals(ProtocolServer.ERROR_DUPLICATE_LOGIN, error.get("code").getAsInt());
        assertEquals("duplicate login", error.get("message").getAsString());
        assertTrue(c1.messagesOfType("error").isEmpty());
    }

    @Test
    void fromEqualsToMoveRejected() {
        StartedGame game = startGame();
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "b", 2, true));

        JsonObject rejected = game.red.lastOfType("moveResult");
        assertFalse(rejected.get("valid").getAsBoolean());
        JsonObject error = game.red.lastOfType("error");
        assertEquals(ProtocolServer.ERROR_ILLEGAL_MOVE, error.get("code").getAsInt());
        assertTrue(game.black.messagesOfType("moveResult").isEmpty());
    }

    @Test
    void oversizedFrameClosesConnection() {
        ProtocolServer server = newServer();
        FakeChannel channel = new FakeChannel("c1");
        server.onConnected(channel);

        String bigMessage = "{\"messageType\":\"ping\",\"timestamp\":1,\"pad\":\""
                + "x".repeat(1100) + "\"}";
        assertTrue(bigMessage.getBytes(StandardCharsets.UTF_8).length > ProtocolServer.MAX_INBOUND_BYTES);

        server.onMessage(channel, bigMessage);

        assertFalse(channel.isOpen());
    }

    @Test
    void legalMoveIsRecorded(@TempDir Path recordsDir) throws Exception {
        StartedGame game = startGame(65_000, recordsDir);
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "e", 2, true));
        game.server.onMessage(game.black, "{\"messageType\":\"Resign\"}");

        JsonArray moves = readRecord(recordsDir, "room_1").getAsJsonArray("moves");
        assertEquals(1, moves.size());
        JsonObject move = moves.get(0).getAsJsonObject();
        assertEquals(1, move.get("moveNo").getAsInt());
        assertEquals("red", move.get("mover").getAsString());
        assertEquals("b", move.get("fromX").getAsString());
        assertEquals(2, move.get("fromY").getAsInt());
        assertEquals("e", move.get("toX").getAsString());
        assertEquals(2, move.get("toY").getAsInt());
        assertTrue(move.get("valid").getAsBoolean());
        assertTrue(move.get("isFlip").getAsBoolean());
        assertFalse(move.get("flipResult").isJsonNull());
        assertTrue(move.has("capturedPiece"));
        assertTrue(move.get("timestamp").getAsLong() > 0);
    }

    @Test
    void illegalMoveIsRecordedWithoutSwitchingTurn(@TempDir Path recordsDir) throws Exception {
        StartedGame game = startGame(65_000, recordsDir);
        game.clear();

        game.server.onMessage(game.red, move("b", 2, "c", 3, true));
        game.server.onMessage(game.red, move("b", 2, "e", 2, true));
        game.server.onMessage(game.black, "{\"messageType\":\"Resign\"}");

        JsonArray moves = readRecord(recordsDir, "room_1").getAsJsonArray("moves");
        assertEquals(2, moves.size());
        JsonObject illegalMove = moves.get(0).getAsJsonObject();
        assertEquals("red", illegalMove.get("mover").getAsString());
        assertEquals("b", illegalMove.get("fromX").getAsString());
        assertEquals(2, illegalMove.get("fromY").getAsInt());
        assertEquals("c", illegalMove.get("toX").getAsString());
        assertEquals(3, illegalMove.get("toY").getAsInt());
        assertFalse(illegalMove.get("valid").getAsBoolean());
        assertTrue(illegalMove.get("flipResult").isJsonNull());
        assertTrue(illegalMove.get("capturedPiece").isJsonNull());

        JsonObject legalMove = moves.get(1).getAsJsonObject();
        assertEquals("red", legalMove.get("mover").getAsString());
        assertTrue(legalMove.get("valid").getAsBoolean());
    }

    private static ProtocolServer newServer() {
        return newServer(65_000, null, true, 0, 80, 0);
    }

    private static ProtocolServer newServer(boolean autoRegisterOnLogin) {
        return newServer(65_000, null, autoRegisterOnLogin, 0, 80, 0);
    }

    private static ProtocolServer newServer(long turnTimeoutMs) {
        return newServer(turnTimeoutMs, null, true, 0, 80, 0);
    }

    private static ProtocolServer newServer(long turnTimeoutMs, Path recordsDir) {
        return newServer(turnTimeoutMs, recordsDir, true, 0, 80, 0);
    }

    private static ProtocolServer newServer(long turnTimeoutMs, Path recordsDir, boolean autoRegisterOnLogin) {
        return newServer(turnTimeoutMs, recordsDir, autoRegisterOnLogin, 0, 80, 0);
    }

    private static ProtocolServer newServer(long turnTimeoutMs, Path recordsDir,
                                            boolean autoRegisterOnLogin, long firstHandWindowMs) {
        return newServer(turnTimeoutMs, recordsDir, autoRegisterOnLogin, firstHandWindowMs, 80, 0);
    }

    private static ProtocolServer newServer(long turnTimeoutMs, Path recordsDir,
                                            boolean autoRegisterOnLogin, long firstHandWindowMs,
                                            int noCaptureLimitHalfMoves) {
        return newServer(turnTimeoutMs, recordsDir, autoRegisterOnLogin, firstHandWindowMs,
                noCaptureLimitHalfMoves, 0);
    }

    private static ProtocolServer newServer(long turnTimeoutMs, Path recordsDir,
                                            boolean autoRegisterOnLogin, long firstHandWindowMs,
                                            int noCaptureLimitHalfMoves, long autoReadyAfterMs) {
        Core.ServerConfig config = new Core.ServerConfig();
        config.usersFile = null;
        config.autoRegisterOnLogin = autoRegisterOnLogin;
        config.turnTimeoutMs = turnTimeoutMs;
        config.firstHandWindowMs = firstHandWindowMs;
        config.autoReadyAfterMs = autoReadyAfterMs;
        config.recordsDir = recordsDir;
        config.noCaptureLimitHalfMoves = noCaptureLimitHalfMoves;
        return new ProtocolServer(config);
    }

    private static StartedGame startGame() {
        return startGame(65_000);
    }

    private static StartedGame startGame(Boolean redWannaFirst, Boolean blackWannaFirst) {
        return startGame(65_000, null, 0, redWannaFirst, blackWannaFirst);
    }

    private static StartedGame startGame(long turnTimeoutMs) {
        return startGame(turnTimeoutMs, null);
    }

    private static StartedGame startGame(long turnTimeoutMs, Path recordsDir) {
        return startGame(turnTimeoutMs, recordsDir, 0, null, null);
    }

    private static StartedGame startGame(long turnTimeoutMs, Path recordsDir,
                                         Boolean redWannaFirst, Boolean blackWannaFirst) {
        return startGame(turnTimeoutMs, recordsDir, 0, redWannaFirst, blackWannaFirst);
    }

    private static StartedGame startGame(long turnTimeoutMs, Path recordsDir, long firstHandWindowMs,
                                         Boolean redWannaFirst, Boolean blackWannaFirst) {
        StartedGame game = matchedGame(turnTimeoutMs, recordsDir, firstHandWindowMs);
        applyFirstHandAndReady(game, redWannaFirst, blackWannaFirst);
        return game;
    }

    private static StartedGame startGameWithNoCaptureLimit(int noCaptureLimitHalfMoves) {
        ProtocolServer server = newServer(65_000, null, true, 0, noCaptureLimitHalfMoves);
        return startGame(server, null, null);
    }

    private static StartedGame startGame(ProtocolServer server,
                                         Boolean redWannaFirst, Boolean blackWannaFirst) {
        StartedGame game = matchedGame(server);
        applyFirstHandAndReady(game, redWannaFirst, blackWannaFirst);
        return game;
    }

    private static void applyFirstHandAndReady(StartedGame game,
                                               Boolean redWannaFirst, Boolean blackWannaFirst) {
        if (redWannaFirst != null) {
            game.server.onMessage(game.red, requestFirstHand(redWannaFirst));
        }
        if (blackWannaFirst != null) {
            game.server.onMessage(game.black, requestFirstHand(blackWannaFirst));
        }
        game.server.onMessage(game.red, "{\"messageType\":\"Ready\"}");
        game.server.onMessage(game.black, "{\"messageType\":\"Ready\"}");
    }

    private static StartedGame matchedGame(long turnTimeoutMs, Path recordsDir, long firstHandWindowMs) {
        ProtocolServer server = newServer(turnTimeoutMs, recordsDir, true, firstHandWindowMs);
        return matchedGame(server);
    }

    private static StartedGame matchedGame(long turnTimeoutMs, Path recordsDir, long firstHandWindowMs,
                                           long autoReadyAfterMs) {
        ProtocolServer server = newServer(turnTimeoutMs, recordsDir, true, firstHandWindowMs,
                80, autoReadyAfterMs);
        return matchedGame(server);
    }

    private static StartedGame matchedGame(ProtocolServer server) {
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
        return new StartedGame(server, red, black);
    }

    private static TwoStartedGames startTwoGames() {
        ProtocolServer server = newServer();
        // 两个房间必须挂在同一个 ProtocolServer 上，才能覆盖 rooms/waiting/session 共享状态的隔离性。
        StartedGame room1 = matchedGame(server, "u1", "u2");
        StartedGame room2 = matchedGame(server, "u3", "u4");
        applyFirstHandAndReady(room1, null, null);
        applyFirstHandAndReady(room2, null, null);
        return new TwoStartedGames(server, room1, room2);
    }

    private static StartedGame matchedGame(ProtocolServer server, String redUserId, String blackUserId) {
        FakeChannel red = new FakeChannel(redUserId);
        FakeChannel black = new FakeChannel(blackUserId);
        server.onConnected(red);
        server.onConnected(black);
        server.onMessage(red, login(redUserId));
        server.onMessage(black, login(blackUserId));
        red.clear();
        black.clear();
        server.onMessage(red, "{\"messageType\":\"startMatch\"}");
        server.onMessage(black, "{\"messageType\":\"startMatch\"}");
        red.clear();
        black.clear();
        return new StartedGame(server, red, black);
    }

    private static Map<String, JsonObject> roomsById(JsonArray rooms) {
        Map<String, JsonObject> byRoomId = new HashMap<>();
        for (int i = 0; i < rooms.size(); i++) {
            JsonObject room = rooms.get(i).getAsJsonObject();
            // rooms 来自服务端 Map，测试按 roomId 取值，避免依赖集合遍历顺序。
            byRoomId.put(room.get("roomId").getAsString(), room);
        }
        return byRoomId;
    }

    private static void assertRoomStatus(JsonObject room, String roomId,
                                         String redPlayerId, String blackPlayerId,
                                         boolean started, boolean finished, String currentTurn) {
        assertTrue(room != null, "room should exist: " + roomId);
        assertEquals(roomId, room.get("roomId").getAsString());
        assertEquals(redPlayerId, room.get("redPlayerId").getAsString());
        assertEquals(blackPlayerId, room.get("blackPlayerId").getAsString());
        assertEquals(started, room.get("started").getAsBoolean());
        assertEquals(finished, room.get("finished").getAsBoolean());
        assertEquals(currentTurn, room.get("currentTurn").getAsString());
    }

    private static JsonObject readRecord(Path recordsDir, String roomId) throws Exception {
        Path file = recordsDir.resolve(roomId + ".json");
        assertTrue(Files.exists(file), "record file should exist: " + file);
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * 为协议可见性测试注入一个最小棋盘。
     *
     * @param game 已完成 gameStart 的测试对局。
     * @param boardText BoardText 格式局面文本。
     * @throws RuntimeException 反射失败时抛出，说明服务端房间结构发生变化，测试需要同步调整。
     * @apiNote 使用示例：{@code setRoomBoard(game, "4k4/9/9/x8/R3P4/9/9/9/9/4K4 r");}
     */
    private static void setRoomBoard(StartedGame game, String boardText) {
        try {
            Object room = roomById(game.server, "room_1");
            Field boardField = room.getClass().getDeclaredField("board");
            boardField.setAccessible(true);
            Core.ServerBoard board = (Core.ServerBoard) boardField.get(room);
            // 生产代码不暴露改盘接口；这里反射只服务于 Q1/Q13 的确定性场景，避免依赖随机翻子推进。
            board.testSetBoard(BoardText.board(boardText));
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("failed to inject test board", ex);
        }
    }

    /**
     * 从协议服务器内部房间表取出指定房间。
     *
     * @param server 测试中的协议服务器。
     * @param roomId 房间编号。
     * @return GameRoom 实例，返回 Object 以避免测试依赖私有内部类类型。
     * @throws ReflectiveOperationException 当私有字段访问失败时抛出。
     * @apiNote 使用示例：{@code Object room = roomById(server, "room_1");}
     */
    @SuppressWarnings("unchecked")
    private static Object roomById(ProtocolServer server, String roomId) throws ReflectiveOperationException {
        Field roomsField = ProtocolServer.class.getDeclaredField("rooms");
        roomsField.setAccessible(true);
        Map<String, Object> rooms = (Map<String, Object>) roomsField.get(server);
        Object room = rooms.get(roomId);
        assertTrue(room != null, "room should exist: " + roomId);
        return room;
    }

    private static void waitUntil(Condition condition) throws Exception {
        long deadline = System.currentTimeMillis() + 1_500;
        while (System.currentTimeMillis() < deadline) {
            if (condition.matches()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(condition.matches(), "condition was not met before timeout");
    }

    private interface Condition {
        boolean matches();
    }

    private static String move(String fromX, int fromY, String toX, int toY, boolean isFlip) {
        return "{\"messageType\":\"move\",\"fromX\":\"" + fromX + "\",\"fromY\":" + fromY
                + ",\"toX\":\"" + toX + "\",\"toY\":" + toY + ",\"isFlip\":" + isFlip + "}";
    }

    private static String requestFirstHand(boolean wannaFirst) {
        return "{\"messageType\":\"requestFirstHand\",\"wannaFirst\":" + wannaFirst + "}";
    }

    private static String login(String userId) {
        return "{\"messageType\":\"Login\",\"userId\":\"" + userId + "\",\"password\":\"p\"}";
    }

    private record StartedGame(ProtocolServer server, FakeChannel red, FakeChannel black) {
        void clear() {
            red.clear();
            black.clear();
        }
    }

    private record TwoStartedGames(ProtocolServer server, StartedGame room1, StartedGame room2) {
        void clear() {
            room1.clear();
            room2.clear();
        }
    }

    private static final class FakeChannel implements Core.ClientChannel {
        private final String remote;
        private final List<String> outbox = new CopyOnWriteArrayList<>();
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
