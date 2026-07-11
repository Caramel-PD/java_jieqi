package client.controller;

import client.network.MessageBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

public class MatchController {
    private final ApplicationController app;

    MatchController(ApplicationController app) {
        this.app = app;
    }

    void handleMatchSuccess(JsonNode root) {
        app.roomId = root.path("roomId").asText();
        app.opponentNickname = root.path("opponentNickname").asText();
        app.opponentReady = false;
        app.myReady = false;
        app.firstHandChoiceMade = false;
        Platform.runLater(() -> {
            if (app.phase != ApplicationController.Phase.MATCHING) {
                return;
            }
            app.matchSuccessPreview = true;
            app.refreshStatusBar();
            PauseTransition transition = new PauseTransition(Duration.millis(800));
            transition.setOnFinished(e -> {
                if (app.phase != ApplicationController.Phase.MATCHING || !app.matchSuccessPreview) {
                    return;
                }
                app.matchSuccessPreview = false;
                app.phase = ApplicationController.Phase.IN_ROOM;
                app.board.setMatchingVisible(false);
                app.board.setLobbyVisible(false);
                app.board.setBoardVisible(false);
                app.refreshStatusBar();
            });
            transition.play();
        });
    }

    void handleRoomInfo(JsonNode root) {
        app.opponentReady = root.path("opponentReady").asBoolean();
        Platform.runLater(app::refreshStatusBar);
    }

    public void startMatch() {
        if (!app.loggedIn || (app.phase != ApplicationController.Phase.IDLE
                && app.phase != ApplicationController.Phase.FINISHED)) {
            return;
        }
        String battleMode = app.board.getSelectedBattleMode();
        if ("ai".equals(battleMode)) {
            requestAiGameRecords();
            return;
        }
        if (!"player".equals(battleMode) && !"player_ai".equals(battleMode)) {
            app.showError("无法匹配", "请先选择对战方式");
            return;
        }
        if (app.wsClient != null && app.wsClient.isOpen()) {
            app.stopReplayPlayback();
            app.phase = ApplicationController.Phase.MATCHING;
            app.roomId = null;
            app.opponentNickname = null;
            app.opponentReady = false;
            app.myReady = false;
            app.firstHandChoiceMade = false;
            app.gameOver = false;
            app.postGameActionsVisible = false;
            app.postGameReasonText = "";
            app.myColor = null;
            app.isMyTurn = false;
            app.pendingMove = false;
            app.matchSuccessPreview = false;
            app.stopTurnTimer();
            app.board.setGameOver(false);
            app.board.setPostGameReason("");
            app.board.setLobbyVisible(false);
            app.board.setBoardVisible(false);
            app.board.hideGameResult();
            app.board.setPostGameActionsVisible(false);
            app.refreshStatusBar();
            if ("player_ai".equals(battleMode)) {
                app.wsClient.send(MessageBuilder.buildStartMatch("pve"));
            } else {
                app.wsClient.send(MessageBuilder.buildStartMatch());
            }
        } else {
            app.showError("无法匹配", "连接已断开");
        }
    }

    public void sendReady() {
        if (app.phase != ApplicationController.Phase.IN_ROOM || app.myReady || app.gameOver) {
            return;
        }
        if (app.wsClient != null && app.wsClient.isOpen()) {
            app.wsClient.send(MessageBuilder.buildReady());
            app.myReady = true;
            app.refreshStatusBar();
        } else {
            app.showError("无法准备", "连接已断开");
        }
    }

    public void requestFirstHand(boolean wannaFirst) {
        if (app.phase != ApplicationController.Phase.IN_ROOM || !app.myReady || !app.opponentReady
                || app.firstHandChoiceMade || app.gameOver) {
            return;
        }
        if (app.wsClient != null && app.wsClient.isOpen()) {
            app.wsClient.send(MessageBuilder.buildRequestFirstHand(wannaFirst));
            app.firstHandChoiceMade = true;
            app.refreshStatusBar();
        } else {
            app.showError("无法选择先手", "连接已断开");
        }
    }

    public void cancelMatch() {
        if (app.phase != ApplicationController.Phase.MATCHING) {
            return;
        }
        if (app.wsClient != null && app.wsClient.isOpen()) {
            app.wsClient.send(MessageBuilder.buildCancelMatch());
            app.phase = ApplicationController.Phase.IDLE;
            app.roomId = null;
            app.opponentNickname = null;
            app.opponentReady = false;
            app.myReady = false;
            app.firstHandChoiceMade = false;
            app.matchSuccessPreview = false;
            app.board.setBoardVisible(false);
            app.refreshStatusBar();
        } else {
            app.showError("无法取消匹配", "连接已断开");
        }
    }

    public void requestAiGameRecords() {
        if (!app.loggedIn) {
            app.showError("无法查看AI对局", "请先登录");
            return;
        }
        if (app.wsClient == null || !app.wsClient.isOpen()) {
            app.showError("无法查看AI对局", "服务器未连接");
            return;
        }
        app.stopReplayPlayback();
        app.phase = ApplicationController.Phase.AI_RECORDS;
        app.aiRecordLookupMode = true;
        app.historyLookupMode = false;
        app.replayLookupRoomId = null;
        app.replayReturnToLobby = true;
        app.board.showAiRecordMessage("正在加载AI对局记录……");
        app.refreshStatusBar();
        app.wsClient.send(MessageBuilder.buildQueryGameRecords(0, 20));
    }

    public void returnFromAiRecordsToLobby() {
        app.aiRecordLookupMode = false;
        app.phase = ApplicationController.Phase.IDLE;
        app.refreshStatusBar();
    }
}
