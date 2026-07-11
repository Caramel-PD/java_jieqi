package client.controller;

import client.network.MessageBuilder;
import client.network.WsClient;
import client.view.LoginView.AuthForm;
import client.view.GameView;
import client.view.GameStatusBar;
import client.view.ReplayView;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.util.Duration;
import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.BoardText;
import jieqi.rules.CellState;
import jieqi.rules.RuleEngine;

import java.util.ArrayList;
import java.util.List;

public class ApplicationController {
    static final int TURN_TIMEOUT_SEC = 65;

    final WsClient wsClient;
    final GameView board;
    final ReplayView replayView;
    final GameStatusBar statusBar;
    final ObjectMapper mapper = new ObjectMapper();

    // 当前玩家颜色（由 gameStart 设置）
    String myColor; // "red" 或 "black"
    boolean isMyTurn = false;
    boolean pendingMove = false;
    boolean gameOver = false;
    boolean matchSuccessPreview = false;
    boolean postGameActionsVisible = false;
    String postGameReasonText = "";
    boolean loggedIn = false;
    String currentUserId;
    BoardSnapshot ruleBoard;

    enum Phase { IDLE, MATCHING, IN_ROOM, PLAYING, FINISHED, REPLAY, AI_RECORDS }
    Phase phase = Phase.IDLE;
    boolean opponentReady = false;
    boolean myReady = false;
    String roomId;
    String opponentNickname;
    boolean firstHandChoiceMade = false;
    boolean registerMode = false;
    AuthForm authForm;
    java.util.function.Consumer<String> authFailedHandler;
    Runnable authSuccessHandler;
    Runnable registerSuccessHandler;
    Runnable rematchHandler;
    Runnable returnLobbyHandler;
    Runnable logoutHandler;

    Timeline turnTimeline;
    PauseTransition postGameTransition;
    final LoginController loginController;
    final MatchController matchController;
    final GameController gameController;
    final ReplayController replayController;
    int remainingSeconds = -1;
    String replayLookupRoomId;
    boolean historyLookupMode = false;
    boolean aiRecordLookupMode = false;
    boolean aiReplayMode = false;
    boolean replayReturnToLobby = false;

    public ApplicationController(WsClient wsClient, GameView board, GameStatusBar statusBar) {
        this.wsClient = wsClient;
        this.board = board;
        this.replayView = new ReplayView(board);
        this.statusBar = statusBar;
        this.loginController = new LoginController(this);
        this.matchController = new MatchController(this);
        this.gameController = new GameController(this);
        this.replayController = new ReplayController(this);
        wsClient.setMessageHandler(this::handleServerMessage);
        statusBar.setResignHandler(gameController::requestResign);
        statusBar.setDrawHandler(gameController::requestDraw);
        statusBar.setReplayPlayHandler(replayController::togglePlayback);
        statusBar.setReplayPrevHandler(replayController::previousStep);
        statusBar.setReplayNextHandler(replayController::nextStep);
        statusBar.setReplayStartHandler(replayController::toStart);
        statusBar.setReplayEndHandler(replayController::toEnd);
        statusBar.setReplayBackHandler(replayController::returnToGameOver);
        statusBar.setReplaySpeedHandler(replayController::setSpeed);
        statusBar.setLogoutHandler(loginController::requestLogout);
        statusBar.setHistoryHandler(replayController::requestHistoryRecords);
        board.setController(gameController);
        board.setStartMatchHandler(matchController::startMatch);
        board.setAiRecordsHandler(matchController::requestAiGameRecords);
        board.setAiRecordsRefreshHandler(matchController::requestAiGameRecords);
        board.setAiRecordsBackHandler(matchController::returnFromAiRecordsToLobby);
        board.setCancelMatchHandler(matchController::cancelMatch);
        board.setFirstHandHandlers(() -> matchController.requestFirstHand(true),
                () -> matchController.requestFirstHand(false));
        board.setReadyHandler(matchController::sendReady);
        board.setPostGameHandlers(replayController::requestReplay, this::requestRematch, this::requestReturnLobby);
        board.setBoardVisible(false);
        board.setLobbyVisible(false);
        board.setMatchingVisible(false);
        board.setAiRecordsVisible(false);
        board.setPostGameActionsVisible(false);
        board.updatePregameControls(false, false, false, false, false);
    }

    public GameController gameController() {
        return gameController;
    }

    public LoginController loginController() {
        return loginController;
    }

    public MatchController matchController() {
        return matchController;
    }

    public void setPostGameNavigationHandlers(Runnable rematchHandler, Runnable returnLobbyHandler) {
        this.rematchHandler = rematchHandler;
        this.returnLobbyHandler = returnLobbyHandler;
    }

    public void setLogoutHandler(Runnable logoutHandler) {
        this.logoutHandler = logoutHandler;
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
                case "loginResult" -> loginController.handleLoginResult(root);
                case "matchSuccess" -> matchController.handleMatchSuccess(root);
                case "roomInfo" -> matchController.handleRoomInfo(root);
                case "gameStart" -> gameController.handleGameStart(root);
                case "moveResult" -> gameController.handleMoveResult(root);
                case "timeout" -> gameController.handleTimeout(root);
                case "gameOver" -> gameController.handleGameOver(root);
                case "drawOffer" -> gameController.handleDrawOffer(root);
                case "drawResponseResult" -> gameController.handleDrawResponseResult(root);
                case "gameRecordList" -> replayController.handleGameRecordList(root);
                case "gameRecord" -> replayController.handleGameRecord(root);
                case "pong" -> handlePong(root);
                case "error" -> handleError(root);
                default -> System.out.println("未知消息类型: " + type);
            }
        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> showError("解析服务器消息出错", e.getMessage()));
        }
    }

    // ========== 服务器通用消息 ==========
    private void handlePong(JsonNode root) {
        // 仅记录日志，不做额外处理。
        long ts = root.path("timestamp").asLong();
        System.out.println("收到 pong, timestamp=" + ts);
    }

    private void handleError(JsonNode root) {
        int code = root.path("code").asInt();
        String msg = root.path("message").asText();
        Platform.runLater(() -> showError("服务器错误(code " + code + ")", msg));
    }

    void showGameResultOverlay(String winner) {
        if ("draw".equals(winner)) {
            board.showDraw();
        } else if (myColor != null && winner.equals(myColor)) {
            board.showVictory();
        } else {
            board.showDefeat();
        }
    }

    String buildPostGameReasonText(String winner, String reason) {
        String result = resultLabel(winner);
        String normalized = reason == null ? "" : reason.trim().toLowerCase();
        return switch (normalized) {
            case "checkmate" -> result + "：" + loserSideText(winner) + "将帅被吃";
            case "timeout" -> result + "：" + loserSideText(winner) + "超时";
            case "resign" -> result + "：" + loserSideText(winner) + "投降";
            case "stalemate" -> result + "：" + loserSideText(winner) + "无合法着法";
            case "disconnect" -> result + "：" + loserSideText(winner) + "断线";
            case "repetition_loss" -> result + "：" + loserSideText(winner) + "长将/长捉";
            case "repetition_draw" -> result + "：重复局面判和";
            case "draw_no_capture" -> result + "：连续 40 回合无吃子";
            case "draw_agreed" -> result + "：双方同意和棋";
            default -> result + "：对局结束";
        };
    }

    private String resultLabel(String winner) {
        if ("draw".equals(winner)) {
            return "和棋";
        }
        return myColor != null && winner.equals(myColor) ? "胜利" : "失败";
    }

    private String loserSideText(String winner) {
        if ("draw".equals(winner) || myColor == null || winner == null || winner.isBlank()) {
            return "双方";
        }
        return winner.equals(myColor) ? "对方" : "我方";
    }

    // ========== 状态栏与计时 ==========
    void resetTurnTimer() {
        stopTurnTimer();
        if (gameOver || myColor == null) {
            statusBar.setTimeoutVisible(false);
            statusBar.updateTimeout(-1);
            return;
        }
        remainingSeconds = TURN_TIMEOUT_SEC;
        statusBar.setTimeoutVisible(true);
        statusBar.updateTimeout(remainingSeconds);
        turnTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingSeconds--;
            statusBar.updateTimeout(Math.max(remainingSeconds, 0));
        }));
        turnTimeline.setCycleCount(Timeline.INDEFINITE);
        turnTimeline.play();
    }

    void stopTurnTimer() {
        if (turnTimeline != null) {
            turnTimeline.stop();
            turnTimeline = null;
        }
        remainingSeconds = -1;
    }

    void refreshStatusBar() {
        statusBar.setTimeoutVisible(false);
        if (phase == Phase.REPLAY) {
            board.setLobbyVisible(false);
            board.setMatchingVisible(false);
            board.setAiRecordsVisible(false);
            board.setPostMatchBackgroundVisible(true);
            board.setRoomWaitingVisible(false);
            board.setPostGameActionsVisible(false);
            statusBar.setReplayMode(true);
            statusBar.updateTimeout(-1);
            statusBar.setDrawEnabled(false);
            statusBar.setResignEnabled(false);
            board.updatePregameControls(false, false, false, false, false);
            return;
        }
        statusBar.setReplayMode(false);
        statusBar.setLobbySettingsMode(false);
        if (phase == Phase.AI_RECORDS) {
            board.setLobbyVisible(false);
            board.setMatchingVisible(false);
            board.setAiRecordsVisible(true);
            board.setRoomWaitingVisible(false);
            board.setPostGameActionsVisible(false);
            statusBar.updateTurn("状态：AI 对局记录");
            statusBar.updateRoom("选择棋谱后进入复盘");
            statusBar.updateTimeout(-1);
            statusBar.setDrawEnabled(false);
            statusBar.setResignEnabled(false);
            board.updatePregameControls(false, false, false, false, false);
            return;
        }
        board.setAiRecordsVisible(false);
        if (phase == Phase.FINISHED || gameOver) {
            board.setLobbyVisible(false);
            board.setMatchingVisible(false);
            board.setPostMatchBackgroundVisible(true);
            board.setRoomWaitingVisible(false);
            board.setPostGameActionsVisible(postGameActionsVisible);
            statusBar.updateTurn("当前回合：对局已结束");
            statusBar.updateRoom(roomInfoText());
            statusBar.updateTimeout(-1);
            statusBar.setDrawEnabled(false);
            statusBar.setResignEnabled(false);
            board.updatePregameControls(false, false, false, false, false);
            return;
        }
        if (phase == Phase.MATCHING) {
            board.setLobbyVisible(false);
            board.setMatchingVisible(true);
            board.setRoomWaitingVisible(false);
            board.setPostGameActionsVisible(false);
            board.setMatchingStatus(matchSuccessPreview ? "匹配成功" : "匹配中……", !matchSuccessPreview);
            statusBar.updateTurn(matchSuccessPreview ? "状态：匹配成功" : "状态：匹配中……");
            statusBar.updateRoom(matchSuccessPreview ? "正在进入准备界面" : "正在寻找对手，请稍候");
            statusBar.updateTimeout(-1);
            statusBar.setDrawEnabled(false);
            statusBar.setResignEnabled(false);
            board.updatePregameControls(false, false, false, false, false);
            return;
        }
        if (phase == Phase.IN_ROOM) {
            board.setLobbyVisible(false);
            board.setMatchingVisible(false);
            board.setPostMatchBackgroundVisible(true);
            board.setPostGameActionsVisible(false);
            statusBar.updateTurn("状态：等待准备");
            statusBar.updateRoom(roomInfoText());
            statusBar.updateTimeout(-1);
            statusBar.setDrawEnabled(false);
            statusBar.setResignEnabled(false);
            boolean bothReady = myReady && opponentReady;
            board.setRoomWaitingVisible(myReady && !opponentReady);
            board.updatePregameControls(false, false, bothReady && !firstHandChoiceMade, !myReady, myReady);
            return;
        }
        if (myColor == null) {
            board.setMatchingVisible(false);
            board.setPostMatchBackgroundVisible(false);
            board.setRoomWaitingVisible(false);
            board.setPostGameActionsVisible(false);
            board.setLobbyVisible(loggedIn);
            statusBar.updateTurn(loggedIn ? "状态：大厅" : "当前回合：等待连接");
            statusBar.updateRoom("");
            statusBar.updateTimeout(-1);
            statusBar.setDrawEnabled(false);
            statusBar.setResignEnabled(false);
            statusBar.setLobbySettingsMode(loggedIn);
            board.updatePregameControls(loggedIn, false, false, false, false);
            return;
        }

        board.setLobbyVisible(false);
        board.setMatchingVisible(false);
        board.setPostMatchBackgroundVisible(true);
        board.setRoomWaitingVisible(false);
        board.setPostGameActionsVisible(false);
        statusBar.updateRoom(roomInfoText());
        statusBar.setTimeoutVisible(true);
        statusBar.setDrawEnabled(true);
        statusBar.setResignEnabled(true);
        board.updatePregameControls(false, false, false, false, false);
        String mySide = colorLabel(myColor);
        String opponentSide = colorLabel(opponentColor());
        String currentSide = isMyTurn ? mySide : opponentSide;
        if (pendingMove) {
            statusBar.updateTurn("当前回合：" + mySide + "（我方）- 走子中……");
        } else if (isMyTurn) {
            statusBar.updateTurn("当前回合：" + currentSide + "（我方）");
        } else {
            statusBar.updateTurn("当前回合：" + currentSide + "（对手）");
        }
    }

    String opponentColor() {
        return "red".equals(myColor) ? "black" : "red";
    }

    String colorLabel(String color) {
        return "red".equals(color) ? "红方" : "黑方";
    }

    String roomInfoText() {
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
                sb.append(" | 我方已准备，等待对手……");
            } else if (opponentReady) {
                sb.append(" | 对手已准备");
            } else {
                sb.append(" | 等待双方准备");
            }
        }
        return sb.toString();
    }

    // ========== UI杈呭姪鏂规硶 ==========
    void showInfo(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    void showError(String title, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(content);
            alert.showAndWait();
        });
    }

    void stopReplayPlayback() {
        replayController.stopPlayback();
    }

    private void requestRematch() {
        stopReplayPlayback();
        if (rematchHandler != null) {
            rematchHandler.run();
        }
    }

    private void requestReturnLobby() {
        stopReplayPlayback();
        if (returnLobbyHandler != null) {
            returnLobbyHandler.run();
        }
    }

}

