package client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessageBuilderTest {

    @Test
    public void testPing() {
        JsonObject json = parse(MessageBuilder.buildPing());
        assertEquals("ping", json.get("messageType").getAsString());
        assertTrue(json.has("timestamp"));
    }

    @Test
    public void testLogin() {
        JsonObject json = parse(MessageBuilder.buildLogin("e_test_1", "123456"));
        assertEquals("Login", json.get("messageType").getAsString());
        assertEquals("e_test_1", json.get("userId").getAsString());
        assertEquals("123456", json.get("password").getAsString());
        assertFalse(json.has("nickname"));
        assertFalse(json.has("timestamp"));
    }

    @Test
    public void testRegister() {
        JsonObject json = parse(MessageBuilder.buildRegister("e_test_1", "123456", "E测试用户"));
        assertEquals("register", json.get("messageType").getAsString());
        assertEquals("e_test_1", json.get("userId").getAsString());
        assertEquals("123456", json.get("password").getAsString());
        assertEquals("E测试用户", json.get("nickname").getAsString());
        assertFalse(json.has("timestamp"));
    }

    @Test
    public void testStartMatch() {
        JsonObject json = parse(MessageBuilder.buildStartMatch());
        assertEquals("startMatch", json.get("messageType").getAsString());
    }

    @Test
    public void testReady() {
        JsonObject json = parse(MessageBuilder.buildReady());
        assertEquals("Ready", json.get("messageType").getAsString());
    }

    @Test
    public void testMove() {
        JsonObject json = parse(MessageBuilder.buildMove("a", 0, "b", 1, true));
        assertEquals("move", json.get("messageType").getAsString());
        assertEquals("a", json.get("fromX").getAsString());
        assertEquals(0, json.get("fromY").getAsInt());
        assertEquals("b", json.get("toX").getAsString());
        assertEquals(1, json.get("toY").getAsInt());
        assertTrue(json.get("isFlip").getAsBoolean());
    }

    @Test
    public void testFlipOnly() {
        JsonObject json = parse(MessageBuilder.buildFlipOnly("b", 3));
        assertEquals("b", json.get("fromX").getAsString());
        assertEquals(3, json.get("fromY").getAsInt());
        assertEquals("b", json.get("toX").getAsString());
        assertEquals(3, json.get("toY").getAsInt());
        assertTrue(json.get("isFlip").getAsBoolean());
    }

    @Test
    public void testResign() {
        JsonObject json = parse(MessageBuilder.buildResign());
        assertEquals("Resign", json.get("messageType").getAsString());
    }

    @Test
    public void testCancelMatch() {
        JsonObject json = parse(MessageBuilder.buildCancelMatch());
        assertEquals("cancelMatch", json.get("messageType").getAsString());
    }

    @Test
    public void testRequestFirstHand() {
        JsonObject yes = parse(MessageBuilder.buildRequestFirstHand(true));
        JsonObject no = parse(MessageBuilder.buildRequestFirstHand(false));
        assertEquals("requestFirstHand", yes.get("messageType").getAsString());
        assertTrue(yes.get("wannaFirst").getAsBoolean());
        assertFalse(no.get("wannaFirst").getAsBoolean());
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}