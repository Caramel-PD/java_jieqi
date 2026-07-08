package client;

public class MessageBuilder {

    // ping
    public static String buildPing() {
        return String.format("{\"messageType\":\"ping\",\"timestamp\":%d}", System.currentTimeMillis());
    }

    // Login（带 timestamp）
    public static String buildLogin(String userId, String password) {
        return String.format("{\"messageType\":\"Login\",\"userId\":\"%s\",\"password\":\"%s\",\"timestamp\":%d}",
                userId, password, System.currentTimeMillis());
    }

    // register（带 timestamp）
    public static String buildRegister(String userId, String password, String nickname) {
        return String.format("{\"messageType\":\"register\",\"userId\":\"%s\",\"password\":\"%s\",\"nickname\":\"%s\",\"timestamp\":%d}",
                userId, password, nickname, System.currentTimeMillis());
    }

    // startMatch
    public static String buildStartMatch() {
        return "{\"messageType\":\"startMatch\"}";
    }

    // cancelMatch
    public static String buildCancelMatch() {
        return "{\"messageType\":\"cancelMatch\"}";
    }

    // requestFirstHand
    public static String buildRequestFirstHand(boolean wannaFirst) {
        return String.format("{\"messageType\":\"requestFirstHand\",\"wannaFirst\":%b}", wannaFirst);
    }

    // move
    public static String buildMove(String fromX, int fromY, String toX, int toY, boolean isFlip) {
        return String.format("{\"messageType\":\"move\",\"fromX\":\"%s\",\"fromY\":%d,\"toX\":\"%s\",\"toY\":%d,\"isFlip\":%b}",
                fromX, fromY, toX, toY, isFlip);
    }

    // flipOnly
    public static String buildFlipOnly(String x, int y) {
        return buildMove(x, y, x, y, true);
    }

    // Ready
    public static String buildReady() {
        return "{\"messageType\":\"Ready\"}";
    }

    // Resign
    public static String buildResign() {
        return "{\"messageType\":\"Resign\"}";
    }
}