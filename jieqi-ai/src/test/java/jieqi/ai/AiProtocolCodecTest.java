package jieqi.ai;

import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Json;
import jieqi.common.Move;
import jieqi.common.PieceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiProtocolCodecTest {

    @Test
    void parsesGameStartYourColor() {
        AiGameState state = AiProtocolCodec.parseGameStart(gameStartJson("black"));

        assertEquals(Color.BLACK, state.yourColor());
        assertEquals("r1", state.redPlayerId());
        assertEquals("b1", state.blackPlayerId());
        assertFalse(state.firstHand());
    }

    @Test
    void hiddenInitialBoardPieceDoesNotLeakPieceType() {
        AiGameState state = AiProtocolCodec.parseGameStart(gameStartJson("red"));

        assertTrue(state.playerView().isHidden(jieqi.common.Coord.parse("a0")));
        assertTrue(state.playerView().revealedPieceTypeAt(jieqi.common.Coord.parse("a0")).isEmpty(),
                "visible=false piece field must not become a revealed PieceType");
        assertEquals(PieceType.KING,
                state.playerView().revealedPieceTypeAt(jieqi.common.Coord.parse("e0")).orElseThrow());
    }

    @Test
    void encodesMoveJsonWithFlipFlagFromPlayerView() {
        AiGameState state = AiProtocolCodec.parseGameStart(gameStartJson("red"));

        JsonObject json = Json.parseObject(AiProtocolCodec.encodeMove(
                state.playerView(),
                Move.parse("a0a1")));

        assertEquals("move", Json.optString(json, "messageType", null));
        assertEquals("a", Json.optString(json, "fromX", null));
        assertEquals(0, Json.optInt(json, "fromY", -1));
        assertEquals("a", Json.optString(json, "toX", null));
        assertEquals(1, Json.optInt(json, "toY", -1));
        assertTrue(Json.optBool(json, "isFlip", false));
    }

    @Test
    void parsesValidMoveResult() {
        MoveResultMessage result = AiProtocolCodec.parseMoveResult("""
                {"messageType":"moveResult","success":true,"valid":true,
                 "move":{"fromX":"b","fromY":2,"toX":"e","toY":2,"isFlip":true},
                 "flipResult":"cannon","capturedPiece":"NULL"}
                """);

        assertTrue(result.valid());
        assertEquals(Move.parse("b2e2"), result.move());
        assertTrue(result.isFlip());
        assertEquals(PieceType.CANNON, result.flipResult().orElseThrow());
        assertEquals(MoveResultMessage.CapturedPiece.Kind.UNKNOWN, result.capturedPiece().kind());
    }

    @Test
    void parsesInvalidMoveResult() {
        MoveResultMessage result = AiProtocolCodec.parseMoveResult("""
                {"messageType":"moveResult","success":true,"valid":false,
                 "move":{"fromX":"a","fromY":0,"toX":"a","toY":0,"isFlip":false}}
                """);

        assertFalse(result.valid());
        assertEquals(Move.parse("a0a0"), result.move());
        assertFalse(result.isFlip());
        assertTrue(result.flipResult().isEmpty());
        assertEquals(MoveResultMessage.CapturedPiece.Kind.NONE, result.capturedPiece().kind());
    }

    private static String gameStartJson(String yourColor) {
        return """
                {"messageType":"gameStart","redPlayerId":"r1","blackPlayerId":"b1",
                 "yourColor":"%s","firstHand":false,
                 "initialBoard":[
                   {"x":"a","y":0,"piece":"rook","visible":false},
                   {"x":"e","y":0,"piece":"king","visible":true},
                   {"x":"e","y":9,"piece":"king","visible":true}
                 ]}
                """.formatted(yourColor);
    }
}
