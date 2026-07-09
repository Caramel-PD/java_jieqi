package jieqi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.CellState;
import jieqi.rules.InitialLayout;

/**
 * S→C 消息构造器（设计文档 §4.3 / §4.4 / §4.5）。"严出"：字段名、大小写、类型严格照公共接口字面。
 * capturedPiece / flipResult 为可选字段：无吃子 / 非翻子步一律省略（§4.5）。
 */
final class Messages {

    private Messages() {}

    static String loginResult(boolean success, String message, String userId) {
        JsonObject o = base("loginResult");
        o.addProperty("success", success);
        o.addProperty("message", message);
        if (success && userId != null) o.addProperty("userId", userId);
        return o.toString();
    }

    static String matchSuccess(String roomId, String opponentId, String opponentNickname) {
        JsonObject o = base("matchSuccess");
        o.addProperty("roomId", roomId);
        o.addProperty("opponentId", opponentId);
        o.addProperty("opponentNickname", opponentNickname);
        return o.toString();
    }

    static String roomInfo(boolean opponentReady) {
        JsonObject o = base("roomInfo");
        o.addProperty("opponentReady", opponentReady);
        return o.toString();
    }

    /**
     * gameStart（按接收方定制 yourColor / firstHand；红恒先手 §2.1）。
     * initialBoard 填充方案（§4.4 / Q37）：将帅 visible=true；其余 30 暗子 visible=false、
     * piece=该格虚拟类型（双方公知的标准布局信息，零机密泄露且枚举合法）。
     */
    static String gameStart(String redPlayerId, String blackPlayerId, Color yourColor,
                            BoardSnapshot initial, String initialBoardMode) {
        JsonObject o = base("gameStart");
        o.addProperty("redPlayerId", redPlayerId);
        o.addProperty("blackPlayerId", blackPlayerId);
        o.addProperty("yourColor", yourColor.json());
        o.addProperty("firstHand", yourColor == Color.RED);
        if (!"omit".equalsIgnoreCase(initialBoardMode)) {
            JsonArray arr = new JsonArray();
            for (int r = 0; r < 10; r++) {
                for (int f = 0; f < 9; f++) {
                    Coord c = new Coord(f, r);
                    CellState s = initial.cellAt(c);
                    if (s.isEmpty()) continue;
                    JsonObject cell = new JsonObject();
                    cell.addProperty("x", String.valueOf((char) ('a' + f)));
                    cell.addProperty("y", r);
                    boolean visible = s instanceof CellState.Revealed;
                    PieceType piece = visible
                            ? ((CellState.Revealed) s).type()
                            : InitialLayout.virtualTypeAt(c);
                    cell.addProperty("piece", piece.json());
                    cell.addProperty("visible", visible);
                    arr.add(cell);
                }
            }
            o.add("initialBoard", arr);
        }
        return o.toString();
    }

    /**
     * moveResult（§4.3 / §4.5 差异化广播矩阵）。
     *
     * @param isFlip        服务器纠正后的真值（暗子动必翻、明子动必不翻）
     * @param flipResult    翻出类型（双方均收真值，Q9）；非翻子步传 null 即省略
     * @param capturedPiece 被吃子展示值：真实类型 json / "NULL" / null（无吃子省略）
     */
    static String moveResult(boolean valid, Coord from, Coord to, boolean isFlip,
                             PieceType flipResult, String capturedPiece) {
        JsonObject o = base("moveResult");
        o.addProperty("success", true);
        o.addProperty("valid", valid);
        JsonObject m = new JsonObject();
        m.addProperty("fromX", String.valueOf((char) ('a' + from.file())));
        m.addProperty("fromY", from.rank());
        m.addProperty("toX", String.valueOf((char) ('a' + to.file())));
        m.addProperty("toY", to.rank());
        m.addProperty("isFlip", isFlip);
        o.add("move", m);
        if (flipResult != null) o.addProperty("flipResult", flipResult.json());
        if (capturedPiece != null) o.addProperty("capturedPiece", capturedPiece);
        return o.toString();
    }

    static String timeout(String loserId, String winnerId) {
        JsonObject o = base("timeout");
        o.addProperty("loserId", loserId);
        o.addProperty("winnerId", winnerId);
        o.addProperty("reason", "timeout");
        return o.toString();
    }

    /** winner: "red"/"black"/"draw"（扩展值，§4.3 表）；和棋时 winnerId 省略。 */
    static String gameOver(String winner, String reason, String winnerId) {
        JsonObject o = base("gameOver");
        o.addProperty("winner", winner);
        o.addProperty("reason", reason);
        if (winnerId != null) o.addProperty("winnerId", winnerId);
        return o.toString();
    }

    /**
     * 构造提和通知消息。
     *
     * <p>requestDraw 是 C→S 请求；客户端弹窗需要明确的 S→C 通知，所以单独输出 drawOffer。
     * 该消息只通知对手，不携带棋局状态变更，避免客户端误以为回合或棋盘已经变化。</p>
     *
     * @param requesterId 提和玩家 userId。
     * @return 可直接发送给对手的 JSON 文本。
     */
    static String drawOffer(String requesterId) {
        JsonObject o = base("drawOffer");
        o.addProperty("requesterId", requesterId);
        return o.toString();
    }

    /**
     * 构造拒绝提和的结果通知。
     *
     * <p>接受提和已经由 gameOver 表达；拒绝不会终局，因此只通知提和方本次协商结束。</p>
     *
     * @param accepted 是否接受；当前拒绝路径传 false，保留字段便于客户端统一处理。
     * @param responderId 响应玩家 userId。
     * @return 可直接发送给提和方的 JSON 文本。
     */
    static String drawResponseResult(boolean accepted, String responderId) {
        JsonObject o = base("drawResponseResult");
        o.addProperty("accepted", accepted);
        o.addProperty("responderId", responderId);
        return o.toString();
    }

    static String pong(long timestamp) {
        JsonObject o = base("pong");
        o.addProperty("timestamp", timestamp);
        return o.toString();
    }

    static String error(int code, String message) {
        JsonObject o = base("error");
        o.addProperty("code", code);
        o.addProperty("message", message);
        return o.toString();
    }

    static String serverStatus(int onlineUsers, int waitingUsers, Iterable<RoomStatus> rooms) {
        JsonObject o = base("serverStatus");
        o.addProperty("onlineUsers", onlineUsers);
        o.addProperty("waitingUsers", waitingUsers);
        JsonArray arr = new JsonArray();
        for (RoomStatus room : rooms) {
            JsonObject r = new JsonObject();
            r.addProperty("roomId", room.roomId());
            r.addProperty("redPlayerId", room.redPlayerId());
            r.addProperty("blackPlayerId", room.blackPlayerId());
            r.addProperty("started", room.started());
            r.addProperty("finished", room.finished());
            r.addProperty("currentTurn", room.currentTurn());
            arr.add(r);
        }
        o.add("rooms", arr);
        return o.toString();
    }

    record RoomStatus(String roomId, String redPlayerId, String blackPlayerId,
                      boolean started, boolean finished, String currentTurn) {}

    private static JsonObject base(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("messageType", type);
        return o;
    }
}
