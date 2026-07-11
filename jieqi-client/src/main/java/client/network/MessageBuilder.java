package client.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

public class MessageBuilder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String buildLogin(String userId, String password) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "Login");
        node.put("userId", userId);
        node.put("password", password);
        return node.toString();
    }

    public static String buildRegister(String userId, String password, String nickname) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "register");
        node.put("userId", userId);
        node.put("password", password);
        node.put("nickname", nickname);
        return node.toString();
    }

    public static String buildStartMatch() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "startMatch");
        return node.toString();
    }

    public static String buildStartMatch(String mode) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "startMatch");
        node.put("mode", mode);
        node.put("clientType", "human");
        return node.toString();
    }

    public static String buildCancelMatch() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "cancelMatch");
        return node.toString();
    }

    public static String buildRequestFirstHand(boolean wannaFirst) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "requestFirstHand");
        node.put("wannaFirst", wannaFirst);
        return node.toString();
    }

    public static String buildReady() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "Ready");
        return node.toString();
    }

    public static String buildMove(String fromX, int fromY, String toX, int toY, boolean isFlip) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "move");
        node.put("fromX", fromX);
        node.put("fromY", fromY);
        node.put("toX", toX);
        node.put("toY", toY);
        node.put("isFlip", isFlip);
        return node.toString();
    }

    public static String buildPing() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "ping");
        node.put("timestamp", Instant.now().toEpochMilli());
        return node.toString();
    }

    public static String buildResign() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "Resign");
        return node.toString();
    }

    // ========== 扩展消息（�?计文档�?.7�?==========
    public static String buildDrawRequest() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "requestDraw");
        return node.toString();
    }

    public static String buildDrawResponse(boolean accept) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "drawResponse");
        node.put("accept", accept);
        return node.toString();
    }

    public static String buildQueryGameRecords(int offset, int limit) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "queryGameRecords");
        node.put("offset", offset);
        node.put("limit", limit);
        return node.toString();
    }

    public static String buildQueryGameRecord(String recordId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("messageType", "queryGameRecord");
        node.put("recordId", recordId);
        return node.toString();
    }
}
