package client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.util.Duration;

public class GameController {
    private static final int TURN_TIMEOUT_SEC = 65;

    private final WsClient wsClient;
    private final ChessBoard board;
    private final GameStatusBar statusBar;
    private final ObjectMapper mapper = new ObjectMapper();

    // 当前玩家颜色（由 gameStart 设置）
    private String myColor; // "red" 或 "black"
    private boolean isMyTurn = false;
    private boolean gameOver = false;

    // 用于先手协商：是否已发送过 requestFirstHand
    private boolean firstHandSent = false;

    private Timeline turnTimeline;
    private int remainingSeconds = -1;

    public GameController(WsClient wsClient, ChessBoard board, GameStatusBar statusBar) {
        this.wsClient = wsClient;
        this.board = board;
        this.statusBar = statusBar;
        wsClient.setMessageHandler(this::handleServerMessage);
    }

    private void handleServerMessage(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode typeNode = root.get("messageType");
            if (typeNode == null || typeNode.isNull()) {
                System.out.println("消息缺少 messageType");
                return;
            }
            String type = typeNode.asText();
            System.out.println("[收到] " + type);
            switch (type) {
                case "loginResult" -> handleLoginResult(root);
                case "matchSuccess" -> handleMatchSuccess(root);
                case "roomInfo" -> handleRoomInfo(root);
                case "gameStart" -> handleGameStart(root);
                case "moveResult" -> handleMoveResult(root);
                case "timeout" -> handleTimeout(root);
                case "gameOver" -> handleGameOver(root);
                case "pong" -> handlePong(root);
                case "error" -> handleError(root);
                // 扩展消息（暂不实现）
                case "requestDraw" -> {} // 可忽略或提示
                case "drawResponse" -> {}
                default -> System.out.println("未知消息类型: " + type);
            }
        } catch (Exception e) {
            e.printStackTrace();
            showError("解析服务器消息出错", e.getMessage());
        }
    }

    // ========== 登录结果 ==========
    private void handleLoginResult(JsonNode root) {
        boolean success = root.path("success").asBoolean();
        String message = root.path("message").asText();
        Platform.runLater(() -> {
            if (success) {
                showInfo("登录成功", message);
                // 登录成功后自动开始匹配
                wsClient.send(MessageBuilder.buildStartMatch());
            } else {
                showError("登录失败", message);
            }
        });
    }

    // ========== 匹配成功 ==========
    private void handleMatchSuccess(JsonNode root) {
        String roomId = root.path("roomId").asText();
        String opponentNickname = root.path("opponentNickname").asText();
        firstHandSent = false;
        Platform.runLater(() -> {
            showInfo("匹配成功", "房间: " + roomId + "\n对手: " + opponentNickname);
            wsClient.send(MessageBuilder.buildReady());
            if (!firstHandSent) {
                wsClient.send(MessageBuilder.buildRequestFirstHand(true));
                firstHandSent = true;
            }
        });
    }

    // ========== 房间信息（对手准备状态） ==========
    private void handleRoomInfo(JsonNode root) {
        boolean opponentReady = root.path("opponentReady").asBoolean();
        Platform.runLater(() -> {
            // 可更新UI状态，例如显示"对手已准备"
            if (opponentReady) {
                System.out.println("对手已准备");
            }
        });
    }

    // ========== 游戏开始 ==========
    private void handleGameStart(JsonNode root) {
        JsonNode boardArray = root.get("initialBoard");
        String color = root.path("yourColor").asText();
        boolean firstHand = root.path("firstHand").asBoolean();
        Platform.runLater(() -> {
            myColor = color;
            isMyTurn = firstHand;
            gameOver = false;
            board.setViewForColor(myColor);
            board.loadBoard(boardArray);
            board.setGameOver(false);
            resetTurnTimer();
            refreshStatusBar();
            System.out.println("游戏开始，我方颜色=" + myColor + ", 是否先走=" + firstHand);
        });
    }

    // ========== 走子结果 ==========
    private void handleMoveResult(JsonNode root) {
        boolean success = root.path("success").asBoolean();
        if (!success) {
            String msg = root.path("message").asText("走子无效");
            Platform.runLater(() -> {
                isMyTurn = true;
                refreshStatusBar();
                showError("走子失败", msg);
            });
            return;
        }
        JsonNode moveNode = root.get("move");
        String flipResult = root.has("flipResult") ? root.get("flipResult").asText() : null;
        String captured = root.has("capturedPiece") ? root.get("capturedPiece").asText() : null;
        Platform.runLater(() -> {
            isMyTurn = !isMyTurn;
            board.applyMoveResult(moveNode, flipResult, captured);
            resetTurnTimer();
            refreshStatusBar();
            if (captured != null && !captured.isEmpty() && !"NULL".equals(captured)) {
                System.out.println("吃掉了: " + captured);
            }
        });
    }

    // ========== 超时 ==========
    private void handleTimeout(JsonNode root) {
        String loserId = root.path("loserId").asText();
        String winnerId = root.path("winnerId").asText();
        Platform.runLater(() -> {
            String msg = "超时！" + loserId + " 判负，胜者 " + winnerId;
            stopTurnTimer();
            refreshStatusBar();
            showInfo("超时判负", msg);
            board.setGameOver(true);
            gameOver = true;
        });
    }

    // ========== 游戏结束 ==========
    private void handleGameOver(JsonNode root) {
        String winner = root.path("winner").asText();
        String reason = root.path("reason").asText();
        String winnerId = root.path("winnerId").asText();
        Platform.runLater(() -> {
            String msg = "对局结束\n";
            if ("draw".equals(winner)) {
                msg += "和棋，原因: " + reason;
            } else {
                msg += "胜者: " + winner + " (ID: " + winnerId + ")\n原因: " + reason;
            }
            stopTurnTimer();
            refreshStatusBar();
            showInfo("游戏结束", msg);
            board.setGameOver(true);
            gameOver = true;
        });
    }

    // ========== Pong响应 ==========
    private void handlePong(JsonNode root) {
        // 只做日志，不做额外处理
        long ts = root.path("timestamp").asLong();
        System.out.println("收到 pong, timestamp=" + ts);
    }

    // ========== 错误 ==========
    private void handleError(JsonNode root) {
        int code = root.path("code").asInt();
        String msg = root.path("message").asText();
        Platform.runLater(() -> showError("服务器错误 (code " + code + ")", msg));
    }

    // ========== 对外发送走子 ==========
    public void sendMove(String fromX, int fromY, String toX, int toY, boolean isFlip) {
        if (!isMyTurn) {
            showError("无法走子", "还没轮到你");
            return;
        }
        if (wsClient != null && wsClient.isOpen() && !gameOver) {
            wsClient.send(MessageBuilder.buildMove(fromX, fromY, toX, toY, isFlip));
            isMyTurn = false;
            refreshStatusBar();
        } else {
            showError("无法走子", gameOver ? "对局已结束" : "连接已断开");
        }
    }

    public boolean isMyTurn() {
        return isMyTurn && !gameOver;
    }

    public boolean isMyPiece(boolean red) {
        if (myColor == null) return false;
        return ("red".equals(myColor) && red) || ("black".equals(myColor) && !red);
    }

    private void resetTurnTimer() {
        stopTurnTimer();
        if (gameOver || myColor == null) {
            statusBar.updateTimeout(-1);
            return;
        }
        remainingSeconds = TURN_TIMEOUT_SEC;
        statusBar.updateTimeout(remainingSeconds);
        turnTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingSeconds--;
            statusBar.updateTimeout(Math.max(remainingSeconds, 0));
        }));
        turnTimeline.setCycleCount(Timeline.INDEFINITE);
        turnTimeline.play();
    }

    private void stopTurnTimer() {
        if (turnTimeline != null) {
            turnTimeline.stop();
            turnTimeline = null;
        }
        remainingSeconds = -1;
    }

    private void refreshStatusBar() {
        if (gameOver) {
            statusBar.updateTurn("当前回合：对局已结束");
            statusBar.updateTimeout(-1);
            return;
        }
        if (myColor == null) {
            statusBar.updateTurn("当前回合：等待开局");
            statusBar.updateTimeout(-1);
            return;
        }

        String mySide = colorLabel(myColor);
        String opponentSide = colorLabel(opponentColor());
        if (isMyTurn) {
            statusBar.updateTurn("当前回合：" + mySide + "（你）");
        } else {
            statusBar.updateTurn("当前回合：" + opponentSide + "（对手）");
        }
    }

    private String opponentColor() {
        return "red".equals(myColor) ? "black" : "red";
    }

    private String colorLabel(String color) {
        return "red".equals(color) ? "红方" : "黑方";
    }

    // ========== UI辅助方法 ==========
    private void showInfo(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    private void showError(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    // 取消匹配（可由UI调用）
    public void cancelMatch() {
        wsClient.send(MessageBuilder.buildCancelMatch());
    }

    // 认输
    public void resign() {
        wsClient.send(MessageBuilder.buildResign());
    }
}