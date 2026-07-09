package jieqi.ai;

import com.google.gson.JsonObject;
import jieqi.common.Json;
import jieqi.common.Move;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiClientTest {

    @Test
    void savesGameStateAfterGameStart() {
        AiClient client = new AiClient(AiClientConfig.defaults(), new FixedAgent(Optional.empty()), new ArrayList<>()::add);

        client.handleServerMessage(gameStart(false));

        AiGameState state = client.gameState().orElseThrow();
        assertEquals("r1", state.redPlayerId());
        assertEquals("b1", state.blackPlayerId());
        assertFalse(state.firstHand());
    }

    @Test
    void callsAgentWhenFirstHandAndSendsMove() {
        List<String> outbound = new ArrayList<>();
        FixedAgent agent = new FixedAgent(Optional.of(Move.parse("a0a1")));
        AiClient client = new AiClient(AiClientConfig.defaults(), agent, outbound::add);

        client.handleServerMessage(gameStart(true));

        assertEquals(1, agent.calls);
        assertEquals(Optional.of(Move.parse("a0a1")), client.lastSelectedMove());
        assertEquals(1, outbound.size());
    }

    @Test
    void generatedMoveJsonUsesProtocolCodec() {
        List<String> outbound = new ArrayList<>();
        AiClient client = new AiClient(
                AiClientConfig.defaults(),
                new FixedAgent(Optional.of(Move.parse("a0a1"))),
                outbound::add);

        client.handleServerMessage(gameStart(true));

        JsonObject moveJson = Json.parseObject(client.lastMoveJson().orElseThrow());
        assertEquals("move", Json.optString(moveJson, "messageType", null));
        assertEquals("a", Json.optString(moveJson, "fromX", null));
        assertEquals(0, Json.optInt(moveJson, "fromY", -1));
        assertEquals("a", Json.optString(moveJson, "toX", null));
        assertEquals(1, Json.optInt(moveJson, "toY", -1));
        assertTrue(Json.optBool(moveJson, "isFlip", false));
        assertEquals(outbound.get(0), client.lastMoveJson().orElseThrow());
    }

    @Test
    void canSendHandshakeMessages() {
        List<String> outbound = new ArrayList<>();
        AiClient client = new AiClient(AiClientConfig.defaults(), new FixedAgent(Optional.empty()), outbound::add);

        client.sendRegister();
        client.sendLogin();
        client.sendStartMatch();
        client.sendReady();

        assertEquals("register", messageType(outbound.get(0)));
        assertEquals("Login", messageType(outbound.get(1)));
        assertEquals("startMatch", messageType(outbound.get(2)));
        assertEquals("Ready", messageType(outbound.get(3)));
    }

    @Test
    void loginSuccessStartsMatchAndMatchSuccessSendsReady() {
        List<String> outbound = new ArrayList<>();
        AiClient client = new AiClient(AiClientConfig.defaults(), new FixedAgent(Optional.empty()), outbound::add);

        client.handleServerMessage("""
                {"messageType":"loginResult","success":true,"message":"ok","userId":"ai"}
                """);
        client.handleServerMessage("""
                {"messageType":"matchSuccess","roomId":"r","opponentId":"p","opponentNickname":"P"}
                """);

        assertEquals("startMatch", messageType(outbound.get(0)));
        assertEquals("Ready", messageType(outbound.get(1)));
    }

    @Test
    void registerSuccessStartsMatch() {
        List<String> outbound = new ArrayList<>();
        AiClientConfig config = new AiClientConfig(
                AiClientConfig.DEFAULT_SERVER_URL,
                "ai1",
                "123456",
                "AI",
                10_000L,
                true);
        AiClient client = new AiClient(config, new FixedAgent(Optional.empty()), outbound::add);

        client.handleServerMessage("""
                {"messageType":"registerResult","success":true,"message":"ok","userId":"ai1"}
                """);

        assertEquals("startMatch", messageType(outbound.get(0)));
    }

    @Test
    void moveResultUpdatesPlayerView() {
        List<String> outbound = new ArrayList<>();
        AiClient client = new AiClient(
                AiClientConfig.defaults(),
                new FixedAgent(Optional.of(Move.parse("a0a1"))),
                outbound::add);

        client.handleServerMessage(gameStart(true));
        client.handleServerMessage("""
                {"messageType":"moveResult","success":true,"valid":true,
                 "move":{"fromX":"a","fromY":0,"toX":"a","toY":1,"isFlip":true},
                 "flipResult":"rook"}
                """);

        PlayerView view = client.gameState().orElseThrow().playerView();
        assertFalse(view.isOccupied(jieqi.common.Coord.parse("a0")));
        assertEquals(jieqi.common.PieceType.ROOK,
                view.revealedPieceTypeAt(jieqi.common.Coord.parse("a1")).orElseThrow());
    }

    @Test
    void opponentMoveResultTriggersMoveWhenItBecomesOurTurn() {
        List<String> outbound = new ArrayList<>();
        FixedAgent agent = new FixedAgent(Optional.of(Move.parse("a0a1")));
        AiClient client = new AiClient(AiClientConfig.defaults(), agent, outbound::add);

        client.handleServerMessage(gameStart(false));
        client.handleServerMessage("""
                {"messageType":"moveResult","success":true,"valid":true,
                 "move":{"fromX":"e","fromY":6,"toX":"e","toY":5,"isFlip":true},
                 "flipResult":"pawn"}
                """);

        assertEquals(1, agent.calls);
        assertEquals("move", messageType(outbound.get(0)));
    }

    @Test
    void gameOverStopsFurtherMoves() throws InterruptedException {
        List<String> outbound = new ArrayList<>();
        AiClient client = new AiClient(
                AiClientConfig.defaults(),
                new FixedAgent(Optional.of(Move.parse("a0a1"))),
                outbound::add);

        client.handleServerMessage(gameStart(true));
        client.handleServerMessage("""
                {"messageType":"gameOver","winner":"black","reason":"resign","winnerId":"b1"}
                """);
        Optional<Move> afterStop = client.selectAndSendMove();

        assertTrue(client.stopped());
        assertTrue(client.awaitStopped(1, TimeUnit.MILLISECONDS));
        assertTrue(afterStop.isEmpty());
        assertEquals(1, outbound.size(), "only the firstHand move should have been sent");
    }

    @Test
    void doesNotExposeServerInternalTypes() {
        assertNoServerTypes(AiClient.class);
        assertNoServerTypes(AiClientConfig.class);
    }

    private static String messageType(String json) {
        return Json.optString(Json.parseObject(json), "messageType", null);
    }

    private static void assertNoServerTypes(Class<?> type) {
        for (Field field : type.getDeclaredFields()) {
            assertFalse(field.getGenericType().getTypeName().contains("jieqi.server"), field.toString());
        }
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getGenericReturnType().getTypeName().contains("jieqi.server"), method.toString());
            assertNoServerParameterTypes(method);
        }
        Arrays.stream(type.getDeclaredConstructors()).forEach(AiClientTest::assertNoServerParameterTypes);
    }

    private static void assertNoServerParameterTypes(java.lang.reflect.Executable executable) {
        for (java.lang.reflect.Type parameterType : executable.getGenericParameterTypes()) {
            assertFalse(parameterType.getTypeName().contains("jieqi.server"), executable.toString());
        }
    }

    private static String gameStart(boolean firstHand) {
        return """
                {"messageType":"gameStart","redPlayerId":"r1","blackPlayerId":"b1",
                 "yourColor":"red","firstHand":%s,
                 "initialBoard":[
                   {"x":"a","y":0,"piece":"rook","visible":false},
                   {"x":"e","y":0,"piece":"king","visible":true},
                   {"x":"e","y":3,"piece":"pawn","visible":false},
                   {"x":"e","y":6,"piece":"pawn","visible":false},
                   {"x":"e","y":9,"piece":"king","visible":true}
                 ]}
                """.formatted(firstHand);
    }

    private static final class FixedAgent implements Agent {
        private final Optional<Move> move;
        private int calls;

        private FixedAgent(Optional<Move> move) {
            this.move = move;
        }

        @Override
        public Optional<Move> selectMove(PlayerView view, TimeBudget budget) {
            calls++;
            assertTrue(budget.limitMillis() > 0);
            return move;
        }
    }
}
