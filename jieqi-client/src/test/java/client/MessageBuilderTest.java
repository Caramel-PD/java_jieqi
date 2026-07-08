package client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E-01 单元测试 – 验证消息 JSON 字段正确性
 * 覆盖：ping, Login, register, startMatch, Ready, move, Resign
 */
public class MessageBuilderTest {

    // ---------- 1. ping ----------
    @Test
    public void testPing() {
        String json = MessageBuilder.buildPing();
        assertTrue(json.contains("\"messageType\":\"ping\""));
        assertTrue(json.contains("\"timestamp\""));
    }

    // ---------- 2. Login（必须含 timestamp） ----------
    @Test
    public void testLogin() {
        String json = MessageBuilder.buildLogin("e_test_1", "123456");
        assertTrue(json.contains("\"messageType\":\"Login\""));
        assertTrue(json.contains("\"userId\":\"e_test_1\""));
        assertTrue(json.contains("\"password\":\"123456\""));
        assertTrue(json.contains("\"timestamp\""));
        assertFalse(json.contains("\"nickname\""));
    }

    // ---------- 3. register（必须含 timestamp） ----------
    @Test
    public void testRegister() {
        String json = MessageBuilder.buildRegister("e_test_1", "123456", "E测试用户");
        assertTrue(json.contains("\"messageType\":\"register\""));
        assertTrue(json.contains("\"userId\":\"e_test_1\""));
        assertTrue(json.contains("\"password\":\"123456\""));
        assertTrue(json.contains("\"nickname\":\"E测试用户\""));
        assertTrue(json.contains("\"timestamp\""));
    }

    // ---------- 4. startMatch ----------
    @Test
    public void testStartMatch() {
        String json = MessageBuilder.buildStartMatch();
        assertEquals("{\"messageType\":\"startMatch\"}", json);
    }

    // ---------- 5. Ready ----------
    @Test
    public void testReady() {
        String json = MessageBuilder.buildReady();
        assertEquals("{\"messageType\":\"Ready\"}", json);
    }

    // ---------- 6. move（额外覆盖） ----------
    @Test
    public void testMove() {
        String json = MessageBuilder.buildMove("a", 0, "b", 1, true);
        assertTrue(json.contains("\"messageType\":\"move\""));
        assertTrue(json.contains("\"fromX\":\"a\""));
        assertTrue(json.contains("\"fromY\":0"));
        assertTrue(json.contains("\"toX\":\"b\""));
        assertTrue(json.contains("\"toY\":1"));
        assertTrue(json.contains("\"isFlip\":true"));
    }

    // ---------- 7. flipOnly ----------
    @Test
    public void testFlipOnly() {
        String json = MessageBuilder.buildFlipOnly("b", 3);
        assertTrue(json.contains("\"fromX\":\"b\""));
        assertTrue(json.contains("\"fromY\":3"));
        assertTrue(json.contains("\"toX\":\"b\""));
        assertTrue(json.contains("\"toY\":3"));
        assertTrue(json.contains("\"isFlip\":true"));
    }

    // ---------- 8. Resign ----------
    @Test
    public void testResign() {
        String json = MessageBuilder.buildResign();
        assertEquals("{\"messageType\":\"Resign\"}", json);
    }

    // ---------- 9. cancelMatch（可选） ----------
    @Test
    public void testCancelMatch() {
        String json = MessageBuilder.buildCancelMatch();
        assertEquals("{\"messageType\":\"cancelMatch\"}", json);
    }

    // ---------- 10. requestFirstHand（可选） ----------
    @Test
    public void testRequestFirstHand() {
        String jsonTrue = MessageBuilder.buildRequestFirstHand(true);
        assertTrue(jsonTrue.contains("\"wannaFirst\":true"));
        String jsonFalse = MessageBuilder.buildRequestFirstHand(false);
        assertTrue(jsonFalse.contains("\"wannaFirst\":false"));
    }
}