package client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E-01 单元测试 – 验证消息 JSON 字段正确性
 * 覆盖：ping, Login, register, startMatch, Ready, move, Resign, cancelMatch, requestFirstHand, drawRequest, drawResponse
 */
public class MessageBuilderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testPing() throws Exception {
        String json = MessageBuilder.buildPing();
        JsonNode node = MAPPER.readTree(json);
        assertEquals("ping", node.get("messageType").asText());
        assertTrue(node.has("timestamp"));
        assertTrue(node.get("timestamp").isNumber());
    }

    @Test
    void testLogin() throws Exception {
        String json = MessageBuilder.buildLogin("e_test_1", "123456");
        JsonNode node = MAPPER.readTree(json);
        assertEquals("Login", node.get("messageType").asText());
        assertEquals("e_test_1", node.get("userId").asText());
        assertEquals("123456", node.get("password").asText());
        assertFalse(node.has("nickname"));
    }

    @Test
    void testRegister() throws Exception {
        String json = MessageBuilder.buildRegister("e_test_1", "123456", "E测试用户");
        JsonNode node = MAPPER.readTree(json);
        assertEquals("register", node.get("messageType").asText());
        assertEquals("e_test_1", node.get("userId").asText());
        assertEquals("123456", node.get("password").asText());
        assertEquals("E测试用户", node.get("nickname").asText());
    }

    @Test
    void testStartMatch() throws Exception {
        String json = MessageBuilder.buildStartMatch();
        JsonNode node = MAPPER.readTree(json);
        assertEquals("startMatch", node.get("messageType").asText());
        assertEquals(1, node.size());
    }

    @Test
    void testReady() throws Exception {
        String json = MessageBuilder.buildReady();
        JsonNode node = MAPPER.readTree(json);
        assertEquals("Ready", node.get("messageType").asText());
        assertEquals(1, node.size());
    }

    @Test
    void testMove() throws Exception {
        String json = MessageBuilder.buildMove("a", 0, "b", 1, true);
        JsonNode node = MAPPER.readTree(json);
        assertEquals("move", node.get("messageType").asText());
        assertEquals("a", node.get("fromX").asText());
        assertEquals(0, node.get("fromY").asInt());
        assertEquals("b", node.get("toX").asText());
        assertEquals(1, node.get("toY").asInt());
        assertTrue(node.get("isFlip").asBoolean());
    }

    // 删除了 testFlipOnly，因为原地翻子不被允许

    @Test
    void testResign() throws Exception {
        String json = MessageBuilder.buildResign();
        JsonNode node = MAPPER.readTree(json);
        assertEquals("Resign", node.get("messageType").asText());
        assertEquals(1, node.size());
    }

    @Test
    void testCancelMatch() throws Exception {
        String json = MessageBuilder.buildCancelMatch();
        JsonNode node = MAPPER.readTree(json);
        assertEquals("cancelMatch", node.get("messageType").asText());
        assertEquals(1, node.size());
    }

    @Test
    void testRequestFirstHand() throws Exception {
        String jsonTrue = MessageBuilder.buildRequestFirstHand(true);
        JsonNode node = MAPPER.readTree(jsonTrue);
        assertEquals("requestFirstHand", node.get("messageType").asText());
        assertTrue(node.get("wannaFirst").asBoolean());

        String jsonFalse = MessageBuilder.buildRequestFirstHand(false);
        node = MAPPER.readTree(jsonFalse);
        assertFalse(node.get("wannaFirst").asBoolean());
    }

    @Test
    void testDrawRequest() throws Exception {
        String json = MessageBuilder.buildDrawRequest();
        JsonNode node = MAPPER.readTree(json);
        assertEquals("requestDraw", node.get("messageType").asText());
        assertEquals(1, node.size());
    }

    @Test
    void testDrawResponse() throws Exception {
        String jsonTrue = MessageBuilder.buildDrawResponse(true);
        JsonNode node = MAPPER.readTree(jsonTrue);
        assertEquals("drawResponse", node.get("messageType").asText());
        assertTrue(node.get("accept").asBoolean());

        String jsonFalse = MessageBuilder.buildDrawResponse(false);
        node = MAPPER.readTree(jsonFalse);
        assertFalse(node.get("accept").asBoolean());
    }
}