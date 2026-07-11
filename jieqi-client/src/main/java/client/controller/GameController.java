package client.controller;

import client.network.MessageBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.PauseTransition;
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

public class GameController {
    private final ApplicationController app;

    GameController(ApplicationController app) {
        this.app = app;
    }

    void handleGameStart(JsonNode root) {
        JsonNode boardArray = root.get("initialBoard");
        String color = root.path("yourColor").asText();
        boolean firstHand = root.path("firstHand").asBoolean();
        Platform.runLater(() -> {
            app.stopReplayPlayback();
            app.phase = ApplicationController.Phase.PLAYING;
            app.myColor = color;
            app.isMyTurn = firstHand;
            app.pendingMove = false;
            app.gameOver = false;
            app.postGameActionsVisible = false;
            app.postGameReasonText = "";
            app.matchSuccessPreview = false;
            app.firstHandChoiceMade = true;
            app.ruleBoard = parseRuleBoard(boardArray);
            app.board.setViewForColor(app.myColor);
            app.board.hideGameResult();
            app.board.setPostGameActionsVisible(false);
            app.board.setPostGameReason("");
            app.board.setLobbyVisible(false);
            app.board.setBoardVisible(true);
            app.board.loadBoard(boardArray);
            app.board.setGameOver(false);
            app.board.resetCaptures();
            app.resetTurnTimer();
            app.refreshStatusBar();
            System.out.println("游戏开始，我方颜色=" + app.myColor + ", 是否先走=" + firstHand);
        });
    }

    void handleMoveResult(JsonNode root) {
        boolean valid = root.path("valid").asBoolean(root.path("success").asBoolean());
        if (!valid) {
            String msg = root.path("message").asText("走子无效");
            Platform.runLater(() -> {
                app.pendingMove = false;
                app.isMyTurn = true;
                app.refreshStatusBar();
                app.showError("走子失败", msg);
            });
            return;
        }
        JsonNode moveNode = root.get("move");
        String flipResult = root.has("flipResult") ? root.get("flipResult").asText() : null;
        String captured = root.has("capturedPiece") ? root.get("capturedPiece").asText() : null;
        Platform.runLater(() -> {
            boolean moverIsMe = app.isMyTurn;
            app.pendingMove = false;
            app.isMyTurn = !app.isMyTurn;
            applyRuleMove(moveNode, flipResult);
            app.board.applyMoveResult(moveNode, flipResult, captured);
            recordCapture(moverIsMe, captured);
            app.resetTurnTimer();
            app.refreshStatusBar();
        });
    }

    void handleTimeout(JsonNode root) {
        Platform.runLater(() -> {
            app.stopTurnTimer();
            app.refreshStatusBar();
        });
    }

    void handleGameOver(JsonNode root) {
        String winner = root.path("winner").asText();
        String reason = root.path("reason").asText("");
        Platform.runLater(() -> {
            app.stopTurnTimer();
            app.gameOver = true;
            app.phase = ApplicationController.Phase.FINISHED;
            app.postGameActionsVisible = false;
            app.postGameReasonText = app.buildPostGameReasonText(winner, reason);
            app.refreshStatusBar();
            app.showGameResultOverlay(winner);
            schedulePostGameActions();
        });
    }

    void handleDrawOffer(JsonNode root) {
        String requesterId = root.path("requesterId").asText("对手");
        Platform.runLater(() -> {
            if (app.gameOver || app.phase != ApplicationController.Phase.PLAYING) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("对手提和");
            confirm.setHeaderText(null);
            confirm.setContentText(requesterId + " 请求和棋，是否接受？");
            confirm.showAndWait().ifPresentOrElse(btn -> sendDrawResponse(btn == ButtonType.OK),
                    () -> sendDrawResponse(false));
        });
    }

    void handleDrawResponseResult(JsonNode root) {
        boolean accepted = root.path("accepted").asBoolean(false);
        String responderId = root.path("responderId").asText("对手");
        Platform.runLater(() -> {
            if (!accepted && !app.gameOver) {
                app.showInfo("和棋被拒绝", responderId + " 拒绝了和棋请求，对局继续。");
            }
        });
    }

    public void sendMove(String fromX, int fromY, String toX, int toY, boolean isFlip) {
        if (!app.isMyTurn || app.pendingMove) {
            app.showError("无法走子", app.pendingMove ? "请等待服务器响应" : "还没轮到我方");
            return;
        }
        if (!isLocallyLegal(fromX, fromY, toX, toY)) {
            app.showError("无法走子", "这一步不符合揭棋规则");
            return;
        }
        if (app.wsClient != null && app.wsClient.isOpen() && !app.gameOver) {
            app.pendingMove = true;
            app.wsClient.send(MessageBuilder.buildMove(fromX, fromY, toX, toY, isFlip));
            app.refreshStatusBar();
        } else {
            app.showError("无法走子", app.gameOver ? "对局已结束" : "连接已断开");
        }
    }

    public boolean isMyTurn() {
        return app.isMyTurn && !app.gameOver && !app.pendingMove;
    }

    public boolean isMyPiece(boolean red) {
        if (app.myColor == null) {
            return false;
        }
        return ("red".equals(app.myColor) && red) || ("black".equals(app.myColor) && !red);
    }

    public List<String> legalDestinations(String fromX, int fromY) {
        if (app.ruleBoard == null || app.myColor == null || !isMyTurn()) {
            return List.of();
        }
        jieqi.common.Color side = myRuleColor();
        Coord from = coord(fromX, fromY);
        List<String> result = new ArrayList<>();
        for (Move move : RuleEngine.generateLegalMoves(app.ruleBoard, side)) {
            if (move.from().equals(from)) {
                result.add(move.to().toString());
            }
        }
        return result;
    }

    public void requestResign() {
        if (app.gameOver || app.myColor == null) {
            app.showError("无法投降", "当前没有进行中的对局");
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

    public void requestDraw() {
        if (app.gameOver || app.phase != ApplicationController.Phase.PLAYING || app.myColor == null) {
            app.showError("无法提和", "当前没有进行中的对局");
            return;
        }
        if (app.wsClient == null || !app.wsClient.isOpen()) {
            app.showError("无法提和", "连接已断开");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认提和");
        confirm.setHeaderText(null);
        confirm.setContentText("确定要向对手请求和棋吗？");
        confirm.showAndWait().ifPresent(btn -> {
            if (btn == ButtonType.OK) {
                app.wsClient.send(MessageBuilder.buildDrawRequest());
                app.showInfo("已发送提和", "已向对手发送和棋请求，等待对方回应。");
            }
        });
    }

    private void schedulePostGameActions() {
        if (app.postGameTransition != null) {
            app.postGameTransition.stop();
        }
        app.postGameTransition = new PauseTransition(Duration.seconds(2.5));
        app.postGameTransition.setOnFinished(e -> {
            if (!app.gameOver || app.phase != ApplicationController.Phase.FINISHED) {
                return;
            }
            app.postGameActionsVisible = true;
            app.board.hideGameResult();
            app.board.setBoardVisible(false);
            app.board.setPostGameReason(app.postGameReasonText);
            app.refreshStatusBar();
        });
        app.postGameTransition.play();
    }

    private boolean isLocallyLegal(String fromX, int fromY, String toX, int toY) {
        if (app.ruleBoard == null || app.myColor == null) {
            return true;
        }
        return RuleEngine.validate(app.ruleBoard, myRuleColor(), coord(fromX, fromY), coord(toX, toY)).legal();
    }

    private jieqi.common.Color myRuleColor() {
        return "red".equals(app.myColor) ? jieqi.common.Color.RED : jieqi.common.Color.BLACK;
    }

    private Coord coord(String x, int y) {
        return new Coord(x.charAt(0) - 'a', y);
    }

    private BoardSnapshot parseRuleBoard(JsonNode boardArray) {
        if (boardArray == null || !boardArray.isArray()) {
            return BoardText.board(BoardText.INITIAL);
        }
        CellState[][] cells = new CellState[10][9];
        for (JsonNode cellNode : boardArray) {
            String x = cellNode.path("x").asText();
            int y = cellNode.path("y").asInt();
            String piece = cellNode.path("piece").asText();
            boolean visible = cellNode.path("visible").asBoolean(false);
            if (x == null || x.isBlank()) {
                continue;
            }
            int file = x.charAt(0) - 'a';
            if (file < 0 || file > 8 || y < 0 || y > 9) {
                continue;
            }
            jieqi.common.Color color = y <= 4 ? jieqi.common.Color.RED : jieqi.common.Color.BLACK;
            if (visible) {
                try {
                    cells[y][file] = new CellState.Revealed(color, jieqi.common.PieceType.fromJson(piece));
                } catch (IllegalArgumentException ignored) {
                    cells[y][file] = new CellState.Hidden(color);
                }
            } else {
                cells[y][file] = new CellState.Hidden(color);
            }
        }
        return BoardSnapshot.of(cells);
    }

    private void applyRuleMove(JsonNode moveNode, String flipResult) {
        if (app.ruleBoard == null || moveNode == null) {
            return;
        }
        try {
            Coord from = coord(moveNode.path("fromX").asText(), moveNode.path("fromY").asInt());
            Coord to = coord(moveNode.path("toX").asText(), moveNode.path("toY").asInt());
            jieqi.common.PieceType flipAs = null;
            if (moveNode.path("isFlip").asBoolean(false) && flipResult != null && !flipResult.isBlank()) {
                flipAs = jieqi.common.PieceType.fromJson(flipResult);
            }
            app.ruleBoard = app.ruleBoard.apply(from, to, flipAs);
        } catch (RuntimeException ex) {
            app.ruleBoard = null;
            System.err.println("规则棋盘同步失败，已临时关闭本地校验: " + ex.getMessage());
        }
    }

    private void recordCapture(boolean moverIsMe, String captured) {
        if (captured == null || captured.isEmpty()) {
            return;
        }
        boolean myRed = "red".equals(app.myColor);
        boolean opponentRed = !myRed;
        if (moverIsMe) {
            if (!"NULL".equals(captured)) {
                app.board.addMyCapture(captured, opponentRed);
            }
        } else {
            app.board.addOpponentCapture(captured, myRed);
        }
    }

    private void sendDrawResponse(boolean accept) {
        if (app.wsClient != null && app.wsClient.isOpen() && !app.gameOver) {
            app.wsClient.send(MessageBuilder.buildDrawResponse(accept));
        }
    }

    private void resign() {
        if (app.wsClient != null && app.wsClient.isOpen()) {
            app.wsClient.send(MessageBuilder.buildResign());
        } else {
            app.showError("无法投降", "连接已断开");
        }
    }
}
