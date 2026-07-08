package client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
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
    private boolean pendingMove = false;
    private boolean gameOver = false;

    private enum Phase { IDLE, MATCHING, IN_ROOM, PLAYING, FINISHED }
    private Phase phase = Phase.IDLE;
    private boolean opponentReady = false;
    private boolean myReady = false;
    private String roomId;
    private String opponentNickname;
    private boolean registerMode = false;
    private AuthForm authForm;
    private java.util.function.Consumer<String> authFailedHandler;
    private Runnable authSuccessHandler;
    private Runnable registerSuccessHandler;

    private Timeline turnTimeline;
    private int remainingSeconds = -1;

    public GameController(WsClient wsClient, ChessBoard board, GameStatusBar statusBar) {
        this.wsClient = wsClient;
        this.board = board;
        this.statusBar = statusBar;
        wsClient.setMessageHandler(this::handleServerMessage);
        statusBar.setResignHandler(this::requestResign);
        statusBar.setReadyHandler(this::sendReady);
    }

    void setRegisterMode(boolean registerMode) {
        this.registerMode = registerMode;
    }

    void setAuthForm(AuthForm form, java.util.function.Consumer<String> onFailed, Runnable onSuccess) {
        this.authForm = form;
        this.authFailedHandler = onFailed;
        this.authSuccessHandler = onSuccess;
    }

    void setRegisterSuccessHandler(Runnable handler) {
        this.registerSuccessHandler = handler;
    }

    void clearAuthForm() {
        this.authForm = null;
        this.authFailedHandler = null;
        this.authSuccessHandler = null;
        this.registerSuccessHandler = null;
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
                AuthForm form = authForm;
                if (registerMode) {
                    Runnable onRegisterSuccess = registerSuccessHandler;
                    clearAuthForm();
                    registerMode = false;
                    phase = Phase.IDLE;
                    refreshStatusBar();
                    if (form != null) {
                        form.onAuthSuccess();
                    }
                    if (onRegisterSuccess != null) {
                        onRegisterSuccess.run();
                    }
                } else {
                    Runnable onSuccess = authSuccessHandler;
                    clearAuthForm();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    if (form != null) {
                        form.onAuthSuccess();
                    }
                    phase = Phase.MATCHING;
                    refreshStatusBar();
                    wsClient.send(MessageBuilder.buildStartMatch());
                }
            } else {
                boolean wasRegister = registerMode;
                registerMode = false;
                phase = Phase.IDLE;
                refreshStatusBar();
                notifyAuthFailed(formatAuthError(wasRegister, message));
            }
        });
    }

    private String formatAuthError(boolean register, String serverMessage) {
        if (register) {
            if (serverMessage == null || serverMessage.isBlank()
                    || "register failed".equalsIgnoreCase(serverMessage.trim())) {
                return "注册失败：用户已存在";
            }
            return "注册失败：" + serverMessage;
        }
        if (serverMessage == null || serverMessage.isBlank()
                || "invalid userId or password".equalsIgnoreCase(serverMessage.trim())) {
            return "登录失败：用户ID或密码错误";
        }
        return "登录失败：" + serverMessage;
    }

    private void notifyAuthFailed(String message) {
        java.util.function.Consumer<String> handler = authFailedHandler;
        clearAuthForm();
        if (handler != null) {
            handler.accept(message);
        }
    }

    // ========== 匹配成功 ==========
    private void handleMatchSuccess(JsonNode root) {
        roomId = root.path("roomId").asText();
        opponentNickname = root.path("opponentNickname").asText();
        opponentReady = false;
        myReady = false;
        Platform.runLater(() -> {
            phase = Phase.IN_ROOM;
            refreshStatusBar();
        });
    }

    private void sendReady() {
        if (phase != Phase.IN_ROOM || myReady || gameOver) {
            return;
        }
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.send(MessageBuilder.buildReady());
            myReady = true;
            refreshStatusBar();
        } else {
            showError("无法准备", "连接已断开");
        }
    }

    // ========== 房间信息（对手准备状态） ==========
    private void handleRoomInfo(JsonNode root) {
        opponentReady = root.path("opponentReady").asBoolean();
        Platform.runLater(this::refreshStatusBar);
    }

    // ========== 游戏开始 ==========
    private void handleGameStart(JsonNode root) {
        JsonNode boardArray = root.get("initialBoard");
        String color = root.path("yourColor").asText();
        boolean firstHand = root.path("firstHand").asBoolean();
        Platform.runLater(() -> {
            phase = Phase.PLAYING;
            myColor = color;
            isMyTurn = firstHand;
            pendingMove = false;
            gameOver = false;
            board.setViewForColor(myColor);
            board.hideGameResult();
            board.loadBoard(boardArray);
            board.setGameOver(false);
            board.resetCaptures();
            resetTurnTimer();
            refreshStatusBar();
            System.out.println("游戏开始，我方颜色=" + myColor + ", 是否先走=" + firstHand);
        });
    }

    // ========== 走子结果 ==========
    private void handleMoveResult(JsonNode root) {
        // 服务器 moveResult 里 success 恒为 true，是否合法看 valid 字段
        boolean valid = root.path("valid").asBoolean(root.path("success").asBoolean());
        if (!valid) {
            String msg = root.path("message").asText("走子无效");
            Platform.runLater(() -> {
                pendingMove = false;
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
            boolean moverIsMe = isMyTurn;
            pendingMove = false;
            isMyTurn = !isMyTurn;
            board.applyMoveResult(moveNode, flipResult, captured);
            recordCapture(moverIsMe, captured);
            resetTurnTimer();
            refreshStatusBar();
        });
    }

    // ========== 超时 ==========
    private void handleTimeout(JsonNode root) {
        Platform.runLater(() -> {
            stopTurnTimer();
            refreshStatusBar();
            // 胜负大字由紧随其后的 gameOver 消息统一展示
        });
    }

    // ========== 游戏结束 ==========
    private void handleGameOver(JsonNode root) {
        String winner = root.path("winner").asText();
        Platform.runLater(() -> {
            stopTurnTimer();
            gameOver = true;
            phase = Phase.FINISHED;
            refreshStatusBar();
            showGameResultOverlay(winner);
        });
    }

    private void showGameResultOverlay(String winner) {
        if ("draw".equals(winner)) {
            board.showDraw();
        } else if (myColor != null && winner.equals(myColor)) {
            board.showVictory();
        } else {
            board.showDefeat();
        }
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
        if (!isMyTurn || pendingMove) {
            showError("无法走子", pendingMove ? "请等待服务器响应" : "还没轮到你");
            return;
        }
        if (wsClient != null && wsClient.isOpen() && !gameOver) {
            pendingMove = true;
            wsClient.send(MessageBuilder.buildMove(fromX, fromY, toX, toY, isFlip));
            refreshStatusBar();
        } else {
            showError("无法走子", gameOver ? "对局已结束" : "连接已断开");
        }
    }

    public boolean isMyTurn() {
        return isMyTurn && !gameOver && !pendingMove;
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
        if (phase == Phase.FINISHED || gameOver) {
            statusBar.updateTurn("当前回合：对局已结束");
            statusBar.updateRoom(roomInfoText());
            statusBar.updateTimeout(-1);
            statusBar.setReadyVisible(false, false);
            statusBar.setResignEnabled(false);
            return;
        }
        if (phase == Phase.MATCHING) {
            statusBar.updateTurn("状态：匹配中…");
            statusBar.updateRoom("正在寻找对手，请稍候");
            statusBar.updateTimeout(-1);
            statusBar.setReadyVisible(false, false);
            statusBar.setResignEnabled(false);
            return;
        }
        if (phase == Phase.IN_ROOM) {
            statusBar.updateTurn("状态：等待开局");
            statusBar.updateRoom(roomInfoText());
            statusBar.updateTimeout(-1);
            statusBar.setReadyVisible(true, myReady);
            statusBar.setResignEnabled(false);
            return;
        }
        if (myColor == null) {
            statusBar.updateTurn("当前回合：等待连接");
            statusBar.updateRoom("");
            statusBar.updateTimeout(-1);
            statusBar.setReadyVisible(false, false);
            statusBar.setResignEnabled(false);
            return;
        }

        statusBar.updateRoom(roomInfoText());
        statusBar.setReadyVisible(false, false);
        statusBar.setResignEnabled(true);
        String mySide = colorLabel(myColor);
        String opponentSide = colorLabel(opponentColor());
        String currentSide = isMyTurn ? mySide : opponentSide;
        if (pendingMove) {
            statusBar.updateTurn("当前回合：" + mySide + "（你）— 走子中…");
        } else if (isMyTurn) {
            statusBar.updateTurn("当前回合：" + currentSide + "（你）");
        } else {
            statusBar.updateTurn("当前回合：" + currentSide + "（对手）");
        }
    }

    private String opponentColor() {
        return "red".equals(myColor) ? "black" : "red";
    }

    private String colorLabel(String color) {
        return "red".equals(color) ? "红方" : "黑方";
    }

    private String roomInfoText() {
        if (roomId == null || roomId.isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("房间 ").append(roomId);
        if (opponentNickname != null && !opponentNickname.isBlank()) {
            sb.append(" | 对手: ").append(opponentNickname);
        }
        if (phase == Phase.IN_ROOM) {
            if (myReady && opponentReady) {
                sb.append(" | 双方已准备");
            } else if (myReady) {
                sb.append(" | 你已准备，等待对手…");
            } else if (opponentReady) {
                sb.append(" | 对手已准备");
            } else {
                sb.append(" | 等待双方准备");
            }
        }
        return sb.toString();
    }

    private void recordCapture(boolean moverIsMe, String captured) {
        if (captured == null || captured.isEmpty()) {
            return;
        }
        boolean myRed = "red".equals(myColor);
        boolean opponentRed = !myRed;
        if (moverIsMe) {
            if (!"NULL".equals(captured)) {
                board.addMyCapture(captured, opponentRed);
            }
        } else {
            board.addOpponentCapture(captured, myRed);
        }
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
    public void requestResign() {
        if (gameOver || myColor == null) {
            showError("无法投降", "当前没有进行中的对局");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认投降");
        confirm.setHeaderText(null);
        confirm.setContentText("确定要投降吗？对手将获胜。");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                resign();
            }
        });
    }

    private void resign() {
        if (wsClient != null && wsClient.isOpen()) {
            wsClient.send(MessageBuilder.buildResign());
        } else {
            showError("无法投降", "连接已断开");
        }
    }
}