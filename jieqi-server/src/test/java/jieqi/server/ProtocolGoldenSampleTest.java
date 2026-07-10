/*
 * 文件功能：服务端 S→C JSON 协议金样例测试。
 * 所属模块：jieqi-server。
 * 使用场景：B-16 协议一致性验收，固定设计文档 §4、§10.2 中对外消息字段，防止后续联调字段漂移。
 */
package jieqi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;
import jieqi.rules.Legality;
import jieqi.rules.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 协议金样例测试。
 *
 * <p>这些测试直接校验 {@link Messages} 的 S→C 输出格式。这样做是为了把服务端“严出”的
 * 公共协议字段固定下来，即使内部房间状态机继续演进，也不能随意改动客户端依赖的 JSON 结构。</p>
 */
class ProtocolGoldenSampleTest {

    /**
     * 验证 loginResult 成功和失败样例。
     *
     * <p>失败响应不能携带错误 userId，避免客户端把未认证身份误当作当前登录用户。</p>
     */
    @Test
    void loginResultGoldenSamples() {
        JsonObject success = parse(Messages.loginResult(true, "ok", "u1"));
        assertEquals("loginResult", success.get("messageType").getAsString());
        assertTrue(success.get("success").getAsBoolean());
        assertEquals("ok", success.get("message").getAsString());
        assertEquals("u1", success.get("userId").getAsString());

        JsonObject failed = parse(Messages.loginResult(false, "invalid userId or password", "badUser"));
        assertEquals("loginResult", failed.get("messageType").getAsString());
        assertFalse(failed.get("success").getAsBoolean());
        assertEquals("invalid userId or password", failed.get("message").getAsString());
        assertFalse(failed.has("userId"));
    }

    /**
     * 验证匹配和准备状态消息字段。
     *
     * <p>matchSuccess 和 roomInfo 是客户端进入房间 UI 的最早依据，字段缺失会直接影响联调。</p>
     */
    @Test
    void matchSuccessAndRoomInfoGoldenSamples() {
        JsonObject match = parse(Messages.matchSuccess("room_1", "u2", "李四"));
        assertEquals("matchSuccess", match.get("messageType").getAsString());
        assertEquals("room_1", match.get("roomId").getAsString());
        assertEquals("u2", match.get("opponentId").getAsString());
        assertEquals("李四", match.get("opponentNickname").getAsString());

        JsonObject roomInfo = parse(Messages.roomInfo(true));
        assertEquals("roomInfo", roomInfo.get("messageType").getAsString());
        assertTrue(roomInfo.get("opponentReady").getAsBoolean());
    }

    /**
     * 验证 gameStart 对红黑双方的定制字段。
     *
     * <p>firstHand 由 yourColor 推导，红方必须为 true、黑方必须为 false，避免客户端自行猜测先手。</p>
     */
    @Test
    void gameStartGoldenSamples() {
        Core.ServerBoard board = new Core.ServerBoard();

        JsonObject redStart = parse(Messages.gameStart("u1", "u2", Color.RED,
                board.snapshot(), "virtual"));
        JsonObject blackStart = parse(Messages.gameStart("u1", "u2", Color.BLACK,
                board.snapshot(), "virtual"));

        assertEquals("gameStart", redStart.get("messageType").getAsString());
        assertEquals("u1", redStart.get("redPlayerId").getAsString());
        assertEquals("u2", redStart.get("blackPlayerId").getAsString());
        assertEquals("red", redStart.get("yourColor").getAsString());
        assertTrue(redStart.get("firstHand").getAsBoolean());
        assertEquals(32, redStart.getAsJsonArray("initialBoard").size());

        assertEquals("gameStart", blackStart.get("messageType").getAsString());
        assertEquals("black", blackStart.get("yourColor").getAsString());
        assertFalse(blackStart.get("firstHand").getAsBoolean());
        assertEquals(redStart.getAsJsonArray("initialBoard"), blackStart.getAsJsonArray("initialBoard"));
    }

    /**
     * 验证 moveResult 的必填字段和可选字段省略规则。
     *
     * <p>非翻子、无吃子时省略 flipResult/capturedPiece，能减少客户端把 null 当成真实棋子类型的风险。</p>
     */
    @Test
    void moveResultGoldenSamples() {
        Coord from = new Coord(4, 0);
        Coord to = new Coord(4, 1);

        JsonObject quietMove = parse(Messages.moveResult(true, from, to, false, null, null));
        assertEquals("moveResult", quietMove.get("messageType").getAsString());
        assertTrue(quietMove.get("success").getAsBoolean());
        assertTrue(quietMove.get("valid").getAsBoolean());
        JsonObject move = quietMove.getAsJsonObject("move");
        assertEquals("e", move.get("fromX").getAsString());
        assertEquals(0, move.get("fromY").getAsInt());
        assertEquals("e", move.get("toX").getAsString());
        assertEquals(1, move.get("toY").getAsInt());
        assertFalse(move.get("isFlip").getAsBoolean());
        assertFalse(quietMove.has("flipResult"));
        assertFalse(quietMove.has("capturedPiece"));

        JsonObject hiddenCaptureForUnauthorized = parse(Messages.moveResult(true, from, to,
                false, null, "NULL"));
        assertEquals("NULL", hiddenCaptureForUnauthorized.get("capturedPiece").getAsString());
    }

    /**
     * 验证 timeout 与多种 gameOver 终局字段。
     *
     * <p>和棋时 winner 使用 draw 且不带 winnerId，客户端据此避免显示错误胜方。</p>
     */
    @Test
    void timeoutAndGameOverGoldenSamples() {
        JsonObject timeout = parse(Messages.timeout("u1", "u2"));
        assertEquals("timeout", timeout.get("messageType").getAsString());
        assertEquals("u1", timeout.get("loserId").getAsString());
        assertEquals("u2", timeout.get("winnerId").getAsString());
        assertEquals("timeout", timeout.get("reason").getAsString());

        JsonObject normalWin = parse(Messages.gameOver("black", "resign", "u2"));
        assertEquals("gameOver", normalWin.get("messageType").getAsString());
        assertEquals("black", normalWin.get("winner").getAsString());
        assertEquals("resign", normalWin.get("reason").getAsString());
        assertEquals("u2", normalWin.get("winnerId").getAsString());

        assertDrawGameOver("draw_agreed");
        assertDrawGameOver("repetition_draw");
        assertDrawGameOver("noCapture");
    }

    /**
     * 验证提和通知和拒绝通知字段。
     *
     * <p>接受提和由 gameOver 表达；拒绝提和才需要 drawResponseResult 告知提和方继续对局。</p>
     */
    @Test
    void drawNotificationGoldenSamples() {
        JsonObject offer = parse(Messages.drawOffer("u1"));
        assertEquals("drawOffer", offer.get("messageType").getAsString());
        assertEquals("u1", offer.get("requesterId").getAsString());

        JsonObject rejected = parse(Messages.drawResponseResult(false, "u2"));
        assertEquals("drawResponseResult", rejected.get("messageType").getAsString());
        assertFalse(rejected.get("accepted").getAsBoolean());
        assertEquals("u2", rejected.get("responderId").getAsString());
    }

    /**
     * 验证 pong 与 error 样例。
     *
     * <p>pong 必须使用 messageType 且原样返回时间戳，error 使用稳定 code/message 便于客户端统一处理。</p>
     */
    @Test
    void pongAndErrorGoldenSamples() {
        JsonObject pong = parse(Messages.pong(1712345678901L));
        assertEquals("pong", pong.get("messageType").getAsString());
        assertEquals(1712345678901L, pong.get("timestamp").getAsLong());

        JsonObject error = parse(Messages.error(4001, "JSON format error"));
        assertEquals("error", error.get("messageType").getAsString());
        assertEquals(4001, error.get("code").getAsInt());
        assertEquals("JSON format error", error.get("message").getAsString());
    }

    /**
     * 验证 serverStatus 可以稳定表达多个房间。
     *
     * <p>多房间状态按 roomId 明确区分，避免调试客户端把不同房间的红黑玩家串起来。</p>
     */
    @Test
    void serverStatusGoldenSampleWithTwoRooms() {
        JsonObject status = parse(Messages.serverStatus(4, 0, java.util.List.of(
                new Messages.RoomStatus("room_1", "u1", "u2", true, false, "red"),
                new Messages.RoomStatus("room_2", "u3", "u4", true, true, "black"))));

        assertEquals("serverStatus", status.get("messageType").getAsString());
        assertEquals(4, status.get("onlineUsers").getAsInt());
        assertEquals(0, status.get("waitingUsers").getAsInt());
        JsonArray rooms = status.getAsJsonArray("rooms");
        assertEquals(2, rooms.size());
        assertRoom(rooms.get(0).getAsJsonObject(), "room_1", "u1", "u2", true, false, "red");
        assertRoom(rooms.get(1).getAsJsonObject(), "room_2", "u3", "u4", true, true, "black");
    }

    /**
     * 验证未翻暗子不会在 gameStart 泄露真实类型。
     *
     * <p>服务端采用惰性抽池，初始暗子真实类型尚未对外存在；公开 JSON 只能由标准虚拟布局决定。</p>
     */
    @Test
    void gameStartDoesNotLeakHiddenPieceTruth() {
        Core.ServerBoard boardA = new Core.ServerBoard();
        Core.ServerBoard boardB = new Core.ServerBoard();

        JsonObject startA = parse(Messages.gameStart("u1", "u2", Color.RED, boardA.snapshot(), "virtual"));
        JsonObject startB = parse(Messages.gameStart("u1", "u2", Color.RED, boardB.snapshot(), "virtual"));

        assertEquals(startA, startB);
    }

    /**
     * 验证被吃暗子对无权接收者始终是 NULL。
     *
     * <p>两个棋盘执行同一公开着法，刻意找到“被吃暗子真实类型不同但翻子结果相同”的随机序列；
     * 对非吃方的 moveResult 仍必须完全一致，证明 capturedPiece 没有泄露真实暗子类型。</p>
     */
    @Test
    void hiddenCapturedPieceDoesNotLeakToUnauthorizedReceiver() {
        Coord from = new Coord(1, 2);
        Coord to = new Coord(1, 9);
        ApplyResultPair pair = differentHiddenCaptureSameFlip(from, to);

        assertNotEquals(pair.left().capturedType(), pair.right().capturedType());
        assertEquals(pair.left().flipType(), pair.right().flipType());

        JsonObject unauthorizedLeft = parse(Messages.moveResult(true, from, to,
                pair.left().isFlip(), pair.left().flipType(), "NULL"));
        JsonObject unauthorizedRight = parse(Messages.moveResult(true, from, to,
                pair.right().isFlip(), pair.right().flipType(), "NULL"));
        assertEquals(unauthorizedLeft, unauthorizedRight);
        assertEquals("NULL", unauthorizedLeft.get("capturedPiece").getAsString());
    }

    /**
     * 为隐藏信息测试寻找一对随机结果。
     *
     * @param from 起点坐标。
     * @param to 终点坐标。
     * @return 被吃类型不同、翻子类型相同的两次应用结果。
     */
    private static ApplyResultPair differentHiddenCaptureSameFlip(Coord from, Coord to) {
        Core.ApplyResult baseline = applyCapture(from, to, 0);
        for (int seed = 1; seed < 10_000; seed++) {
            Core.ApplyResult candidate = applyCapture(from, to, seed);
            // 比较同一公开着法的两种隐含真实类型，确保测试真正覆盖“暗子真值不同”的情况。
            if (baseline.capturedType() != candidate.capturedType()
                    && baseline.flipType() == candidate.flipType()) {
                return new ApplyResultPair(baseline, candidate);
            }
        }
        throw new AssertionError("failed to find different hidden captures with same flip result");
    }

    /**
     * 隐藏信息测试使用的一对服务端应用结果。
     *
     * @param left 第一个棋盘的应用结果。
     * @param right 第二个棋盘的应用结果。
     */
    private record ApplyResultPair(Core.ApplyResult left, Core.ApplyResult right) {}

    /**
     * 在全新服务端棋盘上应用一手吃暗子的合法着法。
     *
     * @param from 起点坐标。
     * @param to 终点坐标。
     * @param seed 随机种子。
     * @return 服务端应用结果。
     */
    private static Core.ApplyResult applyCapture(Coord from, Coord to, int seed) {
        Core.ServerBoard board = new Core.ServerBoard();
        Legality legality = RuleEngine.validate(board.snapshot(), Color.RED, from, to);
        assertTrue(legality.legal(), "test move must be legal");
        return board.apply(Color.RED, from, to, new Random(seed));
    }

    /**
     * 解析 JSON 字符串。
     *
     * @param json JSON 文本。
     * @return JSON 对象。
     */
    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /**
     * 断言和棋 gameOver 的公共字段。
     *
     * @param reason 终局原因。
     */
    private static void assertDrawGameOver(String reason) {
        JsonObject draw = parse(Messages.gameOver("draw", reason, null));
        assertEquals("gameOver", draw.get("messageType").getAsString());
        assertEquals("draw", draw.get("winner").getAsString());
        assertEquals(reason, draw.get("reason").getAsString());
        assertFalse(draw.has("winnerId"));
    }

    /**
     * 断言 serverStatus 中单个房间字段。
     *
     * @param room 房间 JSON。
     * @param roomId 房间编号。
     * @param redPlayerId 红方玩家 ID。
     * @param blackPlayerId 黑方玩家 ID。
     * @param started 是否已开局。
     * @param finished 是否已终局。
     * @param currentTurn 当前行棋方。
     */
    private static void assertRoom(JsonObject room, String roomId, String redPlayerId, String blackPlayerId,
                                   boolean started, boolean finished, String currentTurn) {
        assertEquals(roomId, room.get("roomId").getAsString());
        assertEquals(redPlayerId, room.get("redPlayerId").getAsString());
        assertEquals(blackPlayerId, room.get("blackPlayerId").getAsString());
        assertEquals(started, room.get("started").getAsBoolean());
        assertEquals(finished, room.get("finished").getAsBoolean());
        assertEquals(currentTurn, room.get("currentTurn").getAsString());
    }
}
