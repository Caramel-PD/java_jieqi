package client;

import com.google.gson.JsonObject;

public class MessageBuilder {

    public static String buildPing() {
        JsonObject json = base("ping");
        json.addProperty("timestamp", System.currentTimeMillis());
        return json.toString();
    }

    public static String buildLogin(String userId, String password) {
        JsonObject json = base("Login");
        json.addProperty("userId", userId);
        json.addProperty("password", password);
        return json.toString();
    }

    public static String buildRegister(String userId, String password, String nickname) {
        JsonObject json = base("register");
        json.addProperty("userId", userId);
        json.addProperty("password", password);
        json.addProperty("nickname", nickname);
        return json.toString();
    }

    public static String buildStartMatch() {
        return base("startMatch").toString();
    }

    public static String buildCancelMatch() {
        return base("cancelMatch").toString();
    }

    public static String buildRequestFirstHand(boolean wannaFirst) {
        JsonObject json = base("requestFirstHand");
        json.addProperty("wannaFirst", wannaFirst);
        return json.toString();
    }

    public static String buildMove(String fromX, int fromY, String toX, int toY, boolean isFlip) {
        JsonObject json = base("move");
        json.addProperty("fromX", fromX);
        json.addProperty("fromY", fromY);
        json.addProperty("toX", toX);
        json.addProperty("toY", toY);
        json.addProperty("isFlip", isFlip);
        return json.toString();
    }

    public static String buildFlipOnly(String x, int y) {
        return buildMove(x, y, x, y, true);
    }

    public static String buildReady() {
        return base("Ready").toString();
    }

    public static String buildResign() {
        return base("Resign").toString();
    }

    private static JsonObject base(String messageType) {
        JsonObject json = new JsonObject();
        json.addProperty("messageType", messageType);
        return json;
    }
}