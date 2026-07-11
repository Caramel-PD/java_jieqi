package client.controller;

import client.network.MessageBuilder;
import client.view.GameStatusBar;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

final class ReplayController {
    private final ApplicationController app;
    private final List<JsonNode> moves = new ArrayList<>();

    private Timeline timeline;
    private int index = 0;
    private double speed = 1.0;
    private boolean playing = false;

    ReplayController(ApplicationController app) {
        this.app = app;
    }

    void handleGameRecordList(JsonNode root) {
        if (app.aiRecordLookupMode) {
            app.aiRecordLookupMode = false;
            JsonNode records = recordListNode(root);
            List<GameStatusBar.HistoryRecord> aiRecords = new ArrayList<>();
            if (records.isArray()) {
                for (JsonNode record : records) {
                    String recordId = record.path("recordId").asText("");
                    if (recordId.isBlank()) {
                        recordId = record.path("roomId").asText("");
                    }
                    if (recordId.isBlank()) {
                        continue;
                    }
                    if (isAiGameRecord(record)) {
                        GameStatusBar.HistoryRecord item =
                                new GameStatusBar.HistoryRecord(recordId, aiRecordSummary(record));
                        aiRecords.add(item);
                    }
                }
            }
            Platform.runLater(() -> {
                if (aiRecords.isEmpty()) {
                    app.board.showAiRecordMessage("暂无AI对局记录");
                } else {
                    app.board.showAiRecords(aiRecords, this::openAiRecord);
                }
            });
            return;
        }
        if (app.historyLookupMode) {
            app.historyLookupMode = false;
            JsonNode records = recordListNode(root);
            List<GameStatusBar.HistoryRecord> historyRecords = new ArrayList<>();
            if (records.isArray()) {
                for (JsonNode record : records) {
                    String redPlayerId = record.path("redPlayerId").asText("");
                    String blackPlayerId = record.path("blackPlayerId").asText("");
                    if (app.currentUserId != null && !app.currentUserId.isBlank()
                            && !app.currentUserId.equals(redPlayerId)
                            && !app.currentUserId.equals(blackPlayerId)) {
                        continue;
                    }
                    String recordId = record.path("recordId").asText("");
                    if (recordId.isBlank()) {
                        recordId = record.path("roomId").asText("");
                    }
                    if (!recordId.isBlank()) {
                        historyRecords.add(new GameStatusBar.HistoryRecord(recordId, historyRecordSummary(record)));
                    }
                }
            }
            Platform.runLater(() -> {
                if (historyRecords.isEmpty()) {
                    app.statusBar.showHistoryMessage("暂无历史棋局");
                } else {
                    app.statusBar.showHistoryRecords(historyRecords, this::openHistoryRecord);
                }
            });
            return;
        }
        if (app.replayLookupRoomId == null || app.replayLookupRoomId.isBlank()) {
            return;
        }
        JsonNode records = recordListNode(root);
        String selectedRecordId = null;
        if (records.isArray()) {
            for (JsonNode record : records) {
                if (app.replayLookupRoomId.equals(record.path("roomId").asText())) {
                    selectedRecordId = record.path("recordId").asText(null);
                    if (selectedRecordId == null || selectedRecordId.isBlank()) {
                        selectedRecordId = record.path("roomId").asText(null);
                    }
                    break;
                }
            }
        }
        String recordId = selectedRecordId;
        Platform.runLater(() -> {
            if (recordId == null || recordId.isBlank()) {
                app.showError("无法复盘", "服务器还没有返回这局的棋谱记录");
                return;
            }
            if (app.wsClient != null && app.wsClient.isOpen()) {
                app.wsClient.send(MessageBuilder.buildQueryGameRecord(recordId));
            }
        });
    }

    void handleGameRecord(JsonNode root) {
        JsonNode record = root.has("record") ? root.path("record") : root.path("gameRecord");
        JsonNode movesNode = record.path("moves");
        Platform.runLater(() -> enterReplay(record, movesNode));
    }

    void requestReplay() {
        if (app.roomId == null || app.roomId.isBlank()) {
            app.showError("无法复盘", "当前没有可查询的房间编号");
            return;
        }
        if (app.wsClient == null || !app.wsClient.isOpen()) {
            app.showError("无法复盘", "连接已断开");
            return;
        }
        app.replayLookupRoomId = app.roomId;
        app.replayReturnToLobby = false;
        app.historyLookupMode = false;
        app.aiRecordLookupMode = false;
        app.aiReplayMode = false;
        app.wsClient.send(MessageBuilder.buildQueryGameRecords(0, 100));
    }

    void requestHistoryRecords() {
        if (!app.loggedIn) {
            app.statusBar.showHistoryMessage("请先登录");
            return;
        }
        if (app.wsClient == null || !app.wsClient.isOpen()) {
            app.statusBar.showHistoryMessage("服务器未连接");
            return;
        }
        app.historyLookupMode = true;
        app.aiRecordLookupMode = false;
        app.aiReplayMode = false;
        app.replayLookupRoomId = null;
        app.wsClient.send(MessageBuilder.buildQueryGameRecords(0, 100));
    }

    void togglePlayback() {
        if (playing) {
            stopPlayback();
        } else {
            startPlayback();
        }
    }

    void stopPlayback() {
        if (timeline != null) {
            timeline.stop();
            timeline = null;
        }
        playing = false;
        app.statusBar.setReplayPlaying(false);
    }

    void setSpeed(Double newSpeed) {
        speed = newSpeed == null ? 1.0 : newSpeed;
        if (playing) {
            startPlayback();
        }
    }

    void previousStep() {
        stopPlayback();
        renderReplayAt(index - 1);
    }

    void nextStep() {
        if (index >= moves.size()) {
            stopPlayback();
            return;
        }
        renderReplayAt(index + 1);
        if (index >= moves.size()) {
            stopPlayback();
        }
    }

    void toStart() {
        stopPlayback();
        renderReplayAt(0);
    }

    void toEnd() {
        stopPlayback();
        renderReplayAt(moves.size());
    }

    void returnToGameOver() {
        stopPlayback();
        moves.clear();
        index = 0;
        app.statusBar.setReplayMode(false);
        if (app.replayReturnToLobby) {
            app.replayReturnToLobby = false;
            app.phase = ApplicationController.Phase.IDLE;
            app.gameOver = false;
            app.myColor = null;
            app.roomId = null;
            app.opponentNickname = null;
            app.board.setBoardVisible(false);
            app.board.setPostGameActionsVisible(false);
            app.refreshStatusBar();
            return;
        }
        app.phase = ApplicationController.Phase.FINISHED;
        app.gameOver = true;
        app.postGameActionsVisible = true;
        app.board.setBoardVisible(false);
        app.board.setPostGameReason(app.postGameReasonText);
        app.refreshStatusBar();
    }

    private void enterReplay(JsonNode record, JsonNode movesNode) {
        stopPlayback();
        load(movesNode);
        app.phase = ApplicationController.Phase.REPLAY;
        app.gameOver = true;
        app.pendingMove = false;
        app.isMyTurn = false;
        app.replayLookupRoomId = null;
        app.stopTurnTimer();
        app.board.hideGameResult();
        app.board.setGameOver(true);
        app.board.setPostGameActionsVisible(false);
        app.board.setLobbyVisible(false);
        app.board.setMatchingVisible(false);
        app.statusBar.updateRoom("");
        String replayColor = replayColorFor(record);
        if (replayColor != null) {
            app.myColor = replayColor;
        }
        app.replayView.prepareBoard(replayColor != null ? replayColor : app.myColor);
        app.statusBar.updateReplayStep(0, moves.size());
        renderReplayAt(0);
        app.refreshStatusBar();
    }

    private void load(JsonNode movesNode) {
        stopPlayback();
        moves.clear();
        if (movesNode != null && movesNode.isArray()) {
            for (JsonNode move : movesNode) {
                moves.add(move);
            }
        }
        index = 0;
    }

    private void startPlayback() {
        if (index >= moves.size()) {
            return;
        }
        stopPlayback();
        playing = true;
        app.statusBar.setReplayPlaying(true);
        timeline = new Timeline(new KeyFrame(Duration.millis(intervalMillis()), e -> nextStep()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void renderReplayAt(int targetIndex) {
        index = Math.max(0, Math.min(targetIndex, moves.size()));
        app.replayView.resetBoard();
        for (int i = 0; i < index; i++) {
            JsonNode move = moves.get(i);
            if (move == null || move.isMissingNode()) {
                continue;
            }
            String captured = move.path("capturedPiece").isMissingNode() || move.path("capturedPiece").isNull()
                    ? null : move.path("capturedPiece").asText();
            boolean capturedHidden = app.replayView.applyMove(move, move.path("flipResult").asText(null));
            if (captured != null && !captured.isBlank() && !"NULL".equals(captured)) {
                recordReplayCapture(move.path("mover").asText(), captured, capturedHidden);
            }
        }
        app.statusBar.updateReplayStep(index, moves.size());
        app.statusBar.updateTurn("复盘：" + index + "/" + moves.size());
    }

    private JsonNode recordListNode(JsonNode root) {
        return root.has("records") ? root.path("records") : root.path("gameRecordList");
    }

    private String replayRoomText(JsonNode record) {
        String room = record.path("roomId").asText(app.roomId == null ? "" : app.roomId);
        String recordId = record.path("recordId").asText("");
        if (!recordId.isBlank()) {
            return "复盘房间 " + room + " | 棋谱 " + recordId;
        }
        return room.isBlank() ? "复盘" : "复盘房间 " + room;
    }

    private String replayColorFor(JsonNode record) {
        String redPlayerId = record.path("redPlayerId").asText("");
        String blackPlayerId = record.path("blackPlayerId").asText("");
        if (app.currentUserId != null && app.currentUserId.equals(redPlayerId)) {
            return "red";
        }
        if (app.currentUserId != null && app.currentUserId.equals(blackPlayerId)) {
            return "black";
        }
        return null;
    }

    private String historyRecordSummary(JsonNode record) {
        String red = record.path("redPlayerId").asText("红方");
        String black = record.path("blackPlayerId").asText("黑方");
        String winner = record.path("winner").asText("");
        String reason = historyReasonLabel(record.path("reason").asText(""));
        String endTime = record.path("endTime").asText(record.path("finishedAt").asText(""));
        int moveCount = record.path("moveCount").asInt(record.path("moves").size());
        String myColor = null;
        if (app.currentUserId != null) {
            if (app.currentUserId.equals(red)) {
                myColor = "red";
            } else if (app.currentUserId.equals(black)) {
                myColor = "black";
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(historyResultLabel(winner, myColor));
        if (!reason.isBlank()) {
            sb.append(" · ").append(reason);
        }
        sb.append(" · 红方 ").append(red).append(" / 黑方 ").append(black);
        if (!endTime.isBlank()) {
            sb.append(" · ").append(endTime);
        }
        sb.append(" · ").append(moveCount).append("步");
        return sb.toString();
    }

    private String aiRecordSummary(JsonNode record) {
        String red = record.path("redPlayerId").asText("红方");
        String black = record.path("blackPlayerId").asText("黑方");
        String winner = record.path("winner").asText("");
        String reason = historyReasonLabel(record.path("reason").asText(""));
        String endTime = record.path("endTime").asText(record.path("finishedAt").asText(""));
        int moveCount = record.path("moveCount").asInt(record.path("moves").size());
        StringBuilder sb = new StringBuilder();
        sb.append(aiResultLabel(winner));
        if (!reason.isBlank()) {
            sb.append(" · ").append(reason);
        }
        sb.append(" · 红方 ").append(red).append(" / 黑方 ").append(black);
        if (!endTime.isBlank()) {
            sb.append(" · ").append(endTime);
        }
        sb.append(" · ").append(moveCount).append("步");
        return sb.toString();
    }

    private String aiResultLabel(String winner) {
        if ("red".equalsIgnoreCase(winner)) {
            return "红胜";
        }
        if ("black".equalsIgnoreCase(winner)) {
            return "黑胜";
        }
        return "和";
    }

    private String historyResultLabel(String winner, String myReplayColor) {
        if ("draw".equals(winner)) {
            return "和";
        }
        if (winner == null || winner.isBlank() || myReplayColor == null || myReplayColor.isBlank()) {
            return "和";
        }
        return winner.equals(myReplayColor) ? "胜" : "负";
    }

    private String historyReasonLabel(String reason) {
        if (reason == null) {
            return "";
        }
        return switch (reason.trim().toLowerCase()) {
            case "checkmate" -> "将帅被吃";
            case "timeout" -> "超时";
            case "resign" -> "投降";
            case "stalemate" -> "无合法着法";
            case "disconnect" -> "断线";
            case "repetition_loss" -> "长将/长捉判负";
            case "repetition_draw" -> "重复局面判和";
            case "draw_no_capture" -> "连续 40 回合无吃子";
            case "draw_agreed" -> "双方同意和棋";
            default -> reason;
        };
    }

    private boolean isAiGameRecord(JsonNode record) {
        return isAiPlayerId(record.path("redPlayerId").asText(""))
                && isAiPlayerId(record.path("blackPlayerId").asText(""));
    }

    private boolean isAiPlayerId(String playerId) {
        return playerId != null && playerId.toLowerCase().contains("ai");
    }

    private void recordReplayCapture(String mover, String captured, boolean capturedHidden) {
        boolean moverIsMe = app.myColor != null && app.myColor.equals(mover);
        boolean capturedRed = !"red".equals(mover);
        if (moverIsMe) {
            app.replayView.addMyCapture(captured, capturedRed);
        } else {
            app.replayView.addOpponentCapture(captured, capturedRed, capturedHidden);
        }
    }

    private void openAiRecord(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return;
        }
        if (app.wsClient == null || !app.wsClient.isOpen()) {
            app.board.showAiRecordMessage("服务器未连接");
            return;
        }
        app.replayReturnToLobby = true;
        app.aiReplayMode = true;
        app.wsClient.send(MessageBuilder.buildQueryGameRecord(recordId));
    }

    private void openHistoryRecord(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            return;
        }
        if (app.wsClient == null || !app.wsClient.isOpen()) {
            app.statusBar.showHistoryMessage("服务器未连接");
            return;
        }
        app.replayReturnToLobby = true;
        app.aiReplayMode = false;
        app.wsClient.send(MessageBuilder.buildQueryGameRecord(recordId));
    }

    private double intervalMillis() {
        return Math.max(180.0, 1000.0 / Math.max(0.5, speed));
    }
}
