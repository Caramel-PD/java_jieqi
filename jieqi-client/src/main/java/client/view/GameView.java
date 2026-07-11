package client.view;

import client.controller.GameController;
import client.model.PieceType;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.RotateTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.PixelReader;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javafx.util.Duration;

import jieqi.common.Coord;
import jieqi.rules.InitialLayout;

public class GameView extends Pane {
    private static final int CELL = 70;
    private static final int OFFSET_X = 50;
    private static final int CAPTURE_TOP = 58;
    private static final int CAPTURE_BOTTOM = 58;
    private static final int OFFSET_Y = 50 + CAPTURE_TOP;
    private static final int CAPTURE_ICON = 36;
    private static final int CAPTURE_GAP = 5;
    private static final int CAPTURE_EDGE_GAP = 6;
    private static final int BOARD_FRAME_PAD = 12;

    private static final Color WOOD_SURFACE = Color.web("#F0E2C8");
    private static final Color WOOD_FRAME = Color.web("#D4B88C");
    private static final Color WOOD_FRAME_BORDER = Color.web("#B8956A");
    private static final Color GRID_LINE = Color.web("#6B4E2E");
    private static final Color RIVER_TEXT = Color.web("#5D4037");
    private static final String PANE_BG = "#F7F0E3";

    private final Map<String, ChessPiece> pieceMap = new HashMap<>();
    private final Map<String, Rectangle> hitAreas = new HashMap<>();
    private final List<int[]> palaceDiagonals = new ArrayList<>();
    private final List<Line> palaceLines = new ArrayList<>();
    private final Pane myCapturesPane = new Pane();
    private final Pane opponentCapturesPane = new Pane();
    private final Pane moveHintsPane = new Pane();
    private final Pane boardLayer = new Pane();
    private final LobbyView lobbyView;
    private final MatchView matchingView;
    private final AiRecordsView aiRecordsView;
    private final ResultView postGameView;
    private final GameResultOverlay resultOverlay = new GameResultOverlay();

    private String selectedPos = null;
    private final Set<String> legalTargets = new HashSet<>();
    private GameController controller;
    private boolean gameOver = false;
    private boolean flipped = false;
    private TranslateTransition moveTransition;
    private final Deque<MoveAnimation> moveAnimations = new ArrayDeque<>();

    public GameView(GameController controller) {
        this.controller = controller;
        boardLayer.setVisible(false);
        boardLayer.setManaged(false);
        lobbyView = new LobbyView(this);
        matchingView = new MatchView(this);
        aiRecordsView = new AiRecordsView(this);
        getChildren().add(boardLayer);
        drawBoard();
        createCellHitAreas();
        createMoveHintsLayer();
        createCaptureOverlays();
        postGameView = new ResultView(this);
        createResultOverlay();
        setStyle("-fx-background-color: " + PANE_BG + ";");
        setPrefSize(OFFSET_X + 8 * CELL + 40, OFFSET_Y + 9 * CELL + CAPTURE_BOTTOM);
    }

    private void createResultOverlay() {
        resultOverlay.prefWidthProperty().bind(widthProperty());
        resultOverlay.prefHeightProperty().bind(heightProperty());
        getChildren().add(resultOverlay);
    }

    public void showVictory() {
        setGameOver(true);
        resultOverlay.showVictory();
    }

    public void showDefeat() {
        setGameOver(true);
        resultOverlay.showDefeat();
    }

    public void showDraw() {
        setGameOver(true);
        resultOverlay.showDraw();
    }

    public void hideGameResult() {
        resultOverlay.hide();
    }

    public void setBoardVisible(boolean visible) {
        boardLayer.setVisible(visible);
        boardLayer.setManaged(visible);
        if (!visible) {
            clearPieces();
            resetCaptures();
        }
    }

    public void setLobbyVisible(boolean visible) {
        lobbyView.setVisible(visible);
    }

    public void setMatchingVisible(boolean visible) {
        matchingView.setMatchingVisible(visible);
    }

    public void setAiRecordsVisible(boolean visible) {
        setPostMatchBackgroundVisible(visible);
        aiRecordsView.setVisible(visible);
    }

    public void showAiRecordMessage(String message) {
        aiRecordsView.showMessage(message);
    }

    public void showAiRecords(List<GameStatusBar.HistoryRecord> records, Consumer<String> openHandler) {
        aiRecordsView.showRecords(records, openHandler);
    }

    public void setPostMatchBackgroundVisible(boolean visible) {
        matchingView.setBackgroundVisible(visible);
    }

    public void setMatchingStatus(String text, boolean canCancel) {
        matchingView.setStatus(text, canCancel);
    }

    public void setRoomWaitingVisible(boolean visible) {
        matchingView.setRoomWaitingVisible(visible);
    }

    public void setPostGameActionsVisible(boolean visible) {
        postGameView.setVisible(visible);
    }

    public void setPostGameReason(String text) {
        postGameView.setReason(text);
    }

    public void setController(GameController controller) {
        this.controller = controller;
    }

    public void setStartMatchHandler(Runnable handler) {
        matchingView.setStartMatchHandler(handler);
    }

    public void setAiRecordsHandler(Runnable handler) {
        matchingView.setAiRecordsHandler(handler);
    }

    public void setAiRecordsRefreshHandler(Runnable handler) {
        aiRecordsView.setRefreshHandler(handler);
    }

    public void setAiRecordsBackHandler(Runnable handler) {
        aiRecordsView.setBackHandler(handler);
    }

    public String getSelectedBattleMode() {
        return matchingView.selectedBattleMode();
    }

    public void setCancelMatchHandler(Runnable handler) {
        matchingView.setCancelHandler(handler);
    }

    public void setFirstHandHandlers(Runnable wantFirst, Runnable declineFirst) {
        matchingView.setFirstHandHandlers(wantFirst, declineFirst);
    }

    public void setReadyHandler(Runnable handler) {
        matchingView.setReadyHandler(handler);
    }

    public void setPostGameHandlers(Runnable replay, Runnable rematch, Runnable returnLobby) {
        postGameView.setHandlers(replay, rematch, returnLobby);
    }

    public void updatePregameControls(boolean showStart, boolean showCancel,
                                      boolean showFirstHand, boolean showReady,
                                      boolean myReady) {
        matchingView.updatePregameControls(showStart, showCancel, showFirstHand, showReady, myReady);
    }

    /** 黑方视�?：己方�?子显示在屏幕下方（仅影响显示，协�?��标不变）�?*/
    public void setViewForColor(String myColor) {
        flipped = "black".equals(myColor);
        refreshPalaceLines();
        hitAreas.forEach((key, hit) -> {
            int file = key.charAt(0) - 'a';
            int rank = Integer.parseInt(key.substring(1));
            layoutHitArea(hit, file, rank);
        });
        pieceMap.values().forEach(ChessPiece::updatePosition);
        refreshMoveHints();
    }

    public void setGameOver(boolean over) {
        this.gameOver = over;
        if (over) {
            pieceMap.values().forEach(p -> p.setHighlight(false));
            selectedPos = null;
            clearMoveHints();
        }
    }

    double cellCenterX(int file) {
        return OFFSET_X + file * CELL;
    }

    double cellCenterY(int rank) {
        return OFFSET_Y + toScreenRow(rank) * CELL;
    }

    void layoutPiece(ChessPiece piece) {
        piece.relocate(cellCenterX(piece.getFile()) - 45, cellCenterY(piece.getRank()) - 45);
    }

    private int toScreenRow(int rank) {
        return flipped ? rank : (9 - rank);
    }

    private void drawBoard() {
        double boardW = 8 * CELL;
        double boardH = 9 * CELL;

        Rectangle frame = new Rectangle(OFFSET_X - BOARD_FRAME_PAD, OFFSET_Y - BOARD_FRAME_PAD,
                boardW + 2.0 * BOARD_FRAME_PAD, boardH + 2.0 * BOARD_FRAME_PAD);
        frame.setFill(WOOD_FRAME);
        frame.setStroke(WOOD_FRAME_BORDER);
        frame.setStrokeWidth(2);
        makeMouseTransparent(frame);
        boardLayer.getChildren().add(frame);

        Rectangle surface = new Rectangle(OFFSET_X, OFFSET_Y, boardW, boardH);
        surface.setFill(WOOD_SURFACE);
        makeMouseTransparent(surface);
        boardLayer.getChildren().add(surface);

        for (int r = 0; r < 10; r++) {
            Line line = new Line(OFFSET_X, OFFSET_Y + r * CELL,
                                 OFFSET_X + 8 * CELL, OFFSET_Y + r * CELL);
            line.setStroke(GRID_LINE);
            makeMouseTransparent(line);
            boardLayer.getChildren().add(line);
        }
        for (int f = 0; f < 9; f++) {
            double x = OFFSET_X + f * CELL;
            if (f > 0 && f < 8) {
                Line top = new Line(x, OFFSET_Y, x, OFFSET_Y + 4 * CELL);
                top.setStroke(GRID_LINE);
                makeMouseTransparent(top);
                boardLayer.getChildren().add(top);
                Line bottom = new Line(x, OFFSET_Y + 5 * CELL, x, OFFSET_Y + 9 * CELL);
                bottom.setStroke(GRID_LINE);
                makeMouseTransparent(bottom);
                boardLayer.getChildren().add(bottom);
            } else {
                Line line = new Line(x, OFFSET_Y, x, OFFSET_Y + 9 * CELL);
                line.setStroke(GRID_LINE);
                makeMouseTransparent(line);
                boardLayer.getChildren().add(line);
            }
        }
        Text river = new Text(OFFSET_X + 200, OFFSET_Y + 330, "楚河        漢界");
        river.setFill(RIVER_TEXT);
        river.setStyle("-fx-font-size:25; -fx-font-family: 'Microsoft YaHei';");
        makeMouseTransparent(river);
        boardLayer.getChildren().add(river);

        palaceDiagonals.clear();
        palaceLines.clear();
        addPalaceDiagonal(3, 0, 5, 2);
        addPalaceDiagonal(5, 0, 3, 2);
        addPalaceDiagonal(3, 7, 5, 9);
        addPalaceDiagonal(5, 7, 3, 9);
    }

    private void addPalaceDiagonal(int f1, int r1, int f2, int r2) {
        Line line = new Line();
        line.setStroke(GRID_LINE);
        makeMouseTransparent(line);
        palaceDiagonals.add(new int[]{f1, r1, f2, r2});
        palaceLines.add(line);
        boardLayer.getChildren().add(line);
        updatePalaceLine(palaceLines.size() - 1);
    }

    private void refreshPalaceLines() {
        for (int i = 0; i < palaceLines.size(); i++) {
            updatePalaceLine(i);
        }
    }

    private void updatePalaceLine(int index) {
        int[] d = palaceDiagonals.get(index);
        Line line = palaceLines.get(index);
        line.setStartX(cellCenterX(d[0]));
        line.setStartY(cellCenterY(d[1]));
        line.setEndX(cellCenterX(d[2]));
        line.setEndY(cellCenterY(d[3]));
    }

    private void createCellHitAreas() {
        for (int file = 0; file < 9; file++) {
            for (int rank = 0; rank < 10; rank++) {
                String key = (char) ('a' + file) + String.valueOf(rank);
                Rectangle hit = new Rectangle(CELL, CELL);
                hit.setFill(Color.TRANSPARENT);
                hit.setOnMouseClicked(e -> handleCellClick(key));
                layoutHitArea(hit, file, rank);
                hitAreas.put(key, hit);
                boardLayer.getChildren().add(hit);
            }
        }
    }

    private void createMoveHintsLayer() {
        moveHintsPane.setMouseTransparent(true);
        boardLayer.getChildren().add(moveHintsPane);
    }

    private static void makeMouseTransparent(javafx.scene.Node node) {
        node.setMouseTransparent(true);
    }

    private void layoutHitArea(Rectangle hit, int file, int rank) {
        hit.relocate(cellCenterX(file) - CELL / 2.0, cellCenterY(rank) - CELL / 2.0);
    }

    private void handleCellClick(String key) {
        if (gameOver || controller == null || selectedPos == null) return;
        if (!controller.isMyTurn()) return;
        if (pieceMap.containsKey(key) || !legalTargets.contains(key)) return;

        attemptMoveTo(key);
    }

    private void attemptMoveTo(String key) {
        ChessPiece from = pieceMap.get(selectedPos);
        if (from == null) {
            clearSelection();
            return;
        }
        if (!controller.isMyPiece(from.getRed())) return;
        if (!legalTargets.contains(key)) return;

        int file = key.charAt(0) - 'a';
        int rank = Integer.parseInt(key.substring(1));
        controller.sendMove(from.getFileLetter(), from.getRank(),
                            String.valueOf((char) ('a' + file)), rank, false);
        clearSelection();
    }

    public void loadBoard(JsonNode boardArray) {
        clearPieces();
        for (JsonNode cellNode : boardArray) {
            String x = cellNode.get("x").asText();
            int y = cellNode.get("y").asInt();
            String piece = cellNode.get("piece").asText();
            boolean visible = cellNode.get("visible").asBoolean();

            int file = x.charAt(0) - 'a';
            int rank = y;
            boolean red = inferPieceColor(rank);
            PieceType type = PieceType.fromJson(piece);
            if (type == null) continue;

            ChessPiece cp = new ChessPiece(this, type, red, file, rank, visible);
            addPiece(cp);
        }
    }

    public void loadReplayInitialBoard() {
        clearPieces();
        resetCaptures();
        clearMoveHints();
        for (int rank = 0; rank < 10; rank++) {
            for (int file = 0; file < 9; file++) {
                jieqi.common.PieceType initialType = InitialLayout.virtualTypeAt(new Coord(file, rank));
                if (initialType == null) {
                    continue;
                }
                PieceType type = PieceType.fromJson(initialType.json());
                if (type == null) {
                    continue;
                }
                boolean visible = type == PieceType.KING;
                ChessPiece cp = new ChessPiece(this, type, inferPieceColor(rank), file, rank, visible);
                addPiece(cp);
            }
        }
    }

    public void applyMoveResult(JsonNode moveNode, String flipResult, String capturedPiece) {
        String fromX = moveNode.get("fromX").asText();
        int fromY = moveNode.get("fromY").asInt();
        String toX = moveNode.get("toX").asText();
        int toY = moveNode.get("toY").asInt();
        boolean isFlip = moveNode.get("isFlip").asBoolean();

        String fromKey = fromX + fromY;
        String toKey = toX + toY;

        if (pieceMap.containsKey(toKey)) {
            removePiece(toKey);
        }

        ChessPiece moving = pieceMap.remove(fromKey);
        if (moving == null) return;

        int newFile = toX.charAt(0) - 'a';
        int newRank = toY;
        moving.setFile(newFile);
        moving.setRank(newRank);
        moving.updatePosition();
        pieceMap.put(toKey, moving);

        if (isFlip && flipResult != null && !flipResult.isEmpty()) {
            PieceType newType = PieceType.fromJson(flipResult);
            if (newType != null) {
                moving.setType(newType);
                moving.reveal();
            }
        }

        selectedPos = null;
        pieceMap.values().forEach(p -> p.setHighlight(false));
        clearMoveHints();
    }

    public void applyMoveResultAnimated(JsonNode moveNode, String flipResult, String capturedPiece) {
        moveAnimations.addLast(new MoveAnimation(moveNode, flipResult, capturedPiece));
        playNextMoveAnimation();
    }

    private void playNextMoveAnimation() {
        if (moveTransition != null || moveAnimations.isEmpty()) {
            return;
        }
        MoveAnimation request = moveAnimations.getFirst();
        JsonNode moveNode = request.moveNode();
        String fromKey = moveNode.path("fromX").asText() + moveNode.path("fromY").asInt();
        ChessPiece moving = pieceMap.get(fromKey);
        if (moving == null || !boardLayer.isVisible()) {
            moveAnimations.removeFirst();
            applyMoveResult(moveNode, request.flipResult(), request.capturedPiece());
            playNextMoveAnimation();
            return;
        }

        int toFile = moveNode.path("toX").asText().charAt(0) - 'a';
        int toRank = moveNode.path("toY").asInt();
        double targetX = cellCenterX(toFile) - 45;
        double targetY = cellCenterY(toRank) - 45;
        moveTransition = new TranslateTransition(Duration.millis(320), moving);
        moveTransition.setByX(targetX - moving.getLayoutX());
        moveTransition.setByY(targetY - moving.getLayoutY());
        moveTransition.setOnFinished(event -> {
            moving.setTranslateX(0);
            moving.setTranslateY(0);
            moveTransition = null;
            moveAnimations.removeFirst();
            applyMoveResult(moveNode, request.flipResult(), request.capturedPiece());
            playNextMoveAnimation();
        });
        moveTransition.play();
    }

    public boolean isMoveAnimationRunning() {
        return moveTransition != null || !moveAnimations.isEmpty();
    }

    public void cancelMoveAnimation() {
        moveAnimations.clear();
        if (moveTransition == null) {
            return;
        }
        var node = moveTransition.getNode();
        moveTransition.stop();
        if (node != null) {
            node.setTranslateX(0);
            node.setTranslateY(0);
        }
        moveTransition = null;
    }

    private record MoveAnimation(JsonNode moveNode, String flipResult, String capturedPiece) {
    }

    public boolean applyReplayMoveResult(JsonNode moveNode, String flipResult) {
        String fromX = moveNode.get("fromX").asText();
        int fromY = moveNode.get("fromY").asInt();
        String toX = moveNode.get("toX").asText();
        int toY = moveNode.get("toY").asInt();
        boolean isFlip = moveNode.get("isFlip").asBoolean();

        String fromKey = fromX + fromY;
        String toKey = toX + toY;

        ChessPiece captured = pieceMap.get(toKey);
        boolean capturedHidden = captured != null && captured.isHidden();
        if (captured != null) {
            removePiece(toKey);
        }

        ChessPiece moving = pieceMap.remove(fromKey);
        if (moving == null) {
            return capturedHidden;
        }

        int newFile = toX.charAt(0) - 'a';
        int newRank = toY;
        moving.setFile(newFile);
        moving.setRank(newRank);
        moving.updatePosition();
        pieceMap.put(toKey, moving);

        if (isFlip && flipResult != null && !flipResult.isEmpty()) {
            PieceType newType = PieceType.fromJson(flipResult);
            if (newType != null) {
                moving.setType(newType);
                moving.revealNow();
            }
        }

        selectedPos = null;
        pieceMap.values().forEach(p -> p.setHighlight(false));
        clearMoveHints();
        return capturedHidden;
    }

    private void addPiece(ChessPiece p) {
        p.setOnMouseClicked(e -> handlePieceClick(p));
        boardLayer.getChildren().add(p);
        String key = p.getFileLetter() + p.getRank();
        pieceMap.put(key, p);
    }

    private void removePiece(String key) {
        ChessPiece p = pieceMap.remove(key);
        if (p != null) {
            boardLayer.getChildren().remove(p);
        }
    }

    private void clearPieces() {
        cancelMoveAnimation();
        boardLayer.getChildren().removeIf(node -> node instanceof ChessPiece);
        pieceMap.clear();
        clearSelection();
    }

    private void handlePieceClick(ChessPiece clicked) {
        if (gameOver || controller == null) return;
        if (!controller.isMyTurn()) return;

        String pos = clicked.getFileLetter() + clicked.getRank();
        if (selectedPos == null) {
            if (!controller.isMyPiece(clicked.getRed())) return;
            selectPiece(clicked);
        } else {
            ChessPiece from = pieceMap.get(selectedPos);
            if (from == null) {
                clearSelection();
                return;
            }
            if (selectedPos.equals(pos)) {
                clearSelection();
                return;
            }
            if (!controller.isMyPiece(from.getRed())) {
                clearSelection();
                return;
            }
            if (controller.isMyPiece(clicked.getRed())) {
                clearSelection();
                selectPiece(clicked);
                return;
            }
            attemptMoveTo(pos);
        }
    }

    private void selectPiece(ChessPiece piece) {
        selectedPos = piece.getFileLetter() + piece.getRank();
        piece.setHighlight(true);
        showMoveHints(controller.legalDestinations(piece.getFileLetter(), piece.getRank()));
    }

    private void clearSelection() {
        if (selectedPos != null) {
            ChessPiece selected = pieceMap.get(selectedPos);
            if (selected != null) {
                selected.setHighlight(false);
            }
        }
        selectedPos = null;
        clearMoveHints();
    }

    private void showMoveHints(List<String> targets) {
        legalTargets.clear();
        legalTargets.addAll(targets);
        refreshMoveHints();
    }

    private void refreshMoveHints() {
        moveHintsPane.getChildren().clear();
        for (String key : legalTargets) {
            int file = key.charAt(0) - 'a';
            int rank = Integer.parseInt(key.substring(1));
            Circle dot = new Circle(cellCenterX(file), cellCenterY(rank), 7);
            dot.setFill(Color.web("#2FB344", 0.78));
            dot.setStroke(Color.web("#E9FFE9", 0.9));
            dot.setStrokeWidth(1.5);
            dot.setMouseTransparent(true);
            moveHintsPane.getChildren().add(dot);
        }
        moveHintsPane.toFront();
    }

    private void clearMoveHints() {
        legalTargets.clear();
        moveHintsPane.getChildren().clear();
    }

    /** �?�?根据�?在半场推�??色；走子后沿用�?子自�??色�??*/
    private boolean inferPieceColor(int rank) {
        return rank <= 4;
    }

    private void createCaptureOverlays() {
        myCapturesPane.setMouseTransparent(true);
        opponentCapturesPane.setMouseTransparent(true);
        boardLayer.getChildren().add(0, opponentCapturesPane);
        boardLayer.getChildren().add(0, myCapturesPane);
    }

    public void addMyCapture(String pieceJson, boolean opponentRed) {
        addMyCapture(pieceJson, opponentRed, false);
    }

    public void addMyCapture(String pieceJson, boolean opponentRed, boolean dimmed) {
        myCapturesPane.getChildren().add(createCaptureIcon(pieceJson, opponentRed, dimmed));
        layoutCaptureIcons(myCapturesPane, false);
    }

    public void addOpponentCapture(String pieceJson, boolean myRed) {
        addOpponentCapture(pieceJson, myRed, false);
    }

    public void addOpponentCapture(String pieceJson, boolean myRed, boolean dimmed) {
        opponentCapturesPane.getChildren().add(createCaptureIcon(pieceJson, myRed, dimmed));
        layoutCaptureIcons(opponentCapturesPane, true);
    }

    public void resetCaptures() {
        myCapturesPane.getChildren().clear();
        opponentCapturesPane.getChildren().clear();
    }

    private ImageView createCaptureIcon(String pieceJson, boolean red) {
        return createCaptureIcon(pieceJson, red, false);
    }

    private ImageView createCaptureIcon(String pieceJson, boolean red, boolean dimmed) {
        ImageView icon = new ImageView();
        icon.setFitWidth(CAPTURE_ICON);
        icon.setFitHeight(CAPTURE_ICON);
        icon.setPreserveRatio(true);
        icon.setSmooth(true);
        if (dimmed) {
            icon.setOpacity(0.55);
        }
        if (pieceJson == null || pieceJson.isEmpty() || "NULL".equals(pieceJson)) {
            setCaptureImage(icon, "/pieces/cover.png");
            return icon;
        }
        PieceType type = PieceType.fromJson(pieceJson);
        if (type == null) {
            setCaptureImage(icon, "/pieces/cover.png");
            return icon;
        }
        String prefix = red ? "red_" : "black_";
        setCaptureImage(icon, "/pieces/" + prefix + type.value() + ".png");
        return icon;
    }

    private void layoutCaptureIcons(Pane pane, boolean top) {
        double boardLeft = OFFSET_X;
        double boardRight = OFFSET_X + 8.0 * CELL;
        double y = top
                ? OFFSET_Y - BOARD_FRAME_PAD - CAPTURE_ICON - CAPTURE_EDGE_GAP
                : OFFSET_Y + 9.0 * CELL + BOARD_FRAME_PAD + CAPTURE_EDGE_GAP;
        for (int i = 0; i < pane.getChildren().size(); i++) {
            double x = top
                    ? boardRight - CAPTURE_ICON - i * (CAPTURE_ICON + CAPTURE_GAP)
                    : boardLeft + i * (CAPTURE_ICON + CAPTURE_GAP);
            pane.getChildren().get(i).relocate(x, y);
        }
    }

    private void setCaptureImage(ImageView icon, String path) {
        Image image = loadCaptureImage(path);
        icon.setImage(image);
        Rectangle2D bounds = visibleBounds(image);
        if (bounds != null) {
            icon.setViewport(bounds);
        }
    }

    private Rectangle2D visibleBounds(Image image) {
        if (image == null) {
            return null;
        }
        PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return null;
        }
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (reader.getArgb(x, y) >>> 24 > 8) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) {
            return null;
        }
        return new Rectangle2D(minX, minY, maxX - minX + 1.0, maxY - minY + 1.0);
    }

    private Image loadCaptureImage(String path) {
        var stream = getClass().getResourceAsStream(path);
        return stream == null ? null : new Image(stream);
    }



}



class ChessPiece extends ImageView {
    private final GameView board;
    private int file;      // 0-8
    private int rank;      // 0-9（协议y值）
    private boolean red;
    private boolean hidden;
    private PieceType type;

    public ChessPiece(GameView board, PieceType type, boolean red, int file, int rank, boolean visible) {
        this.board = board;
        this.type = type;
        this.red = red;
        this.file = file;
        this.rank = rank;
        this.hidden = !visible;
        setFitWidth(90);
        setFitHeight(90);
        if (visible) {
            showRealPiece();
        } else {
            showCover();
        }
        updatePosition();
    }

    public void reveal() {
        if (!hidden) return;
        hidden = false;
        RotateTransition rt = new RotateTransition(Duration.millis(300), this);
        rt.setToAngle(90);
        rt.setOnFinished(e -> {
            showRealPiece();
            setRotate(270);
            RotateTransition back = new RotateTransition(Duration.millis(300), this);
            back.setToAngle(360);
            back.setOnFinished(ev -> setRotate(0));
            back.play();
        });
        rt.play();
    }

    public void revealNow() {
        hidden = false;
        setRotate(0);
        showRealPiece();
    }

    private void showCover() {
        setImage(load("/pieces/cover.png"));
    }

    private void showRealPiece() {
        String prefix = red ? "red_" : "black_";
        String path = "/pieces/" + prefix + type.value() + ".png";
        setImage(load(path));
    }

    public void updatePosition() {
        board.layoutPiece(this);
    }

    private Image load(String path) {
        var stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            System.err.println("棋子图片缺失: " + path);
            return null;
        }
        return new Image(stream);
    }

    // Getters/Setters
    public int getFile() { return file; }
    public int getRank() { return rank; }
    public String getFileLetter() { return String.valueOf((char)('a' + file)); }
    public void setFile(int file) { this.file = file; }
    public void setRank(int rank) { this.rank = rank; }
    public void setType(PieceType type) { this.type = type; }
    public boolean getRed() { return red; }
    public boolean isHidden() { return hidden; }
    public void setHighlight(boolean on) {
        if (on) {
            DropShadow glow = new DropShadow();
            glow.setColor(Color.GOLD);
            glow.setRadius(16);
            glow.setSpread(0.45);
            setEffect(glow);
            setScaleX(1.06);
            setScaleY(1.06);
        } else {
            setEffect(null);
            setScaleX(1.0);
            setScaleY(1.0);
        }
    }
}


class GameResultOverlay extends StackPane {
    private final Rectangle dim = new Rectangle();
    private final Label label = new Label();

    public GameResultOverlay() {
        setVisible(false);
        setMouseTransparent(true);
        setAlignment(Pos.CENTER);
        setPickOnBounds(false);

        dim.setFill(Color.rgb(0, 0, 0, 0.45));
        dim.widthProperty().bind(widthProperty());
        dim.heightProperty().bind(heightProperty());

        label.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 96));
        getChildren().addAll(dim, label);
    }

    public void showVictory() {
        show("胜利", Color.web("#FFD700"), Color.web("#FF8C00"), 28);
    }

    public void showDefeat() {
        show("失败", Color.web("#CFD8DC"), Color.web("#455A64"), 20);
    }

    public void showDraw() {
        show("和棋", Color.web("#A5D6A7"), Color.web("#2E7D32"), 22);
    }

    public void hide() {
        setVisible(false);
        setOpacity(1);
        label.setScaleX(1);
        label.setScaleY(1);
    }

    private void show(String text, Color textColor, Color glowColor, double glowRadius) {
        label.setText(text);
        label.setTextFill(textColor);
        DropShadow glow = new DropShadow();
        glow.setColor(glowColor);
        glow.setRadius(glowRadius);
        glow.setSpread(0.35);
        label.setEffect(glow);

        setOpacity(0);
        label.setScaleX(0.4);
        label.setScaleY(0.4);
        setVisible(true);
        toFront();

        FadeTransition fade = new FadeTransition(Duration.millis(450), this);
        fade.setFromValue(0);
        fade.setToValue(1);

        ScaleTransition scale = new ScaleTransition(Duration.millis(450), label);
        scale.setFromX(0.4);
        scale.setFromY(0.4);
        scale.setToX(1.0);
        scale.setToY(1.0);

        ParallelTransition anim = new ParallelTransition(fade, scale);
        anim.play();
    }
}


final class ViewStyles {
    private ViewStyles() {
    }

    static void battleModeButton(Button button, boolean selected) {
        if (selected) {
            button.setStyle("-fx-background-color: #C9A86C; -fx-text-fill: #20150E;"
                    + " -fx-background-radius: 6; -fx-border-radius: 6;"
                    + " -fx-border-color: #F2D99A; -fx-border-width: 1;");
        } else {
            button.setStyle("-fx-background-color: rgba(250,240,220,0.88); -fx-text-fill: #2A1D14;"
                    + " -fx-background-radius: 6; -fx-border-color: rgba(201,168,108,0.82);"
                    + " -fx-border-radius: 6; -fx-border-width: 1;");
        }
    }

    static void roomActionButton(Button button) {
        button.setStyle("-fx-background-color: rgba(67,45,30,0.90); -fx-text-fill: #F4E5C5;"
                + " -fx-background-radius: 6; -fx-border-color: #C9A86C;"
                + " -fx-border-radius: 6; -fx-border-width: 1;");
    }
}





final class AiRecordsView {
    private final VBox root = new VBox(14);
    private final VBox listBox = new VBox(8);
    private final Text statusText = new Text("AI 对局记录");
    private final Button refreshButton = new Button("刷新");
    private final Button backButton = new Button("返回大厅");

    AiRecordsView(Pane parent) {
        statusText.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 28));
        statusText.setFill(Color.web("#3D2B20"));
        listBox.setAlignment(Pos.CENTER);

        ScrollPane scrollPane = new ScrollPane(listBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportWidth(520);
        scrollPane.setPrefViewportHeight(320);
        scrollPane.setMaxWidth(560);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;"
                + " -fx-border-color: rgba(67,45,30,0.35); -fx-border-radius: 6;"
                + " -fx-background-radius: 6;");

        for (Button button : new Button[]{refreshButton, backButton}) {
            button.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 18));
            button.setFocusTraversable(false);
            button.setPrefWidth(220);
            button.setPrefHeight(48);
            ViewStyles.roomActionButton(button);
        }
        HBox actions = new HBox(12, refreshButton, backButton);
        actions.setAlignment(Pos.CENTER);

        root.setAlignment(Pos.CENTER);
        root.setPadding(Insets.EMPTY);
        root.setStyle("-fx-background-color: transparent;");
        root.getChildren().addAll(statusText, scrollPane, actions);
        root.layoutXProperty().bind(parent.widthProperty().subtract(root.widthProperty()).divide(2));
        root.layoutYProperty().bind(parent.heightProperty().subtract(root.heightProperty()).divide(2));
        root.setVisible(false);
        root.setManaged(false);
        parent.getChildren().add(root);
    }

    void setVisible(boolean visible) {
        root.setVisible(visible);
        root.setManaged(visible);
        if (visible) {
            root.toFront();
        }
    }

    void showMessage(String message) {
        statusText.setText(message == null ? "" : message);
        listBox.getChildren().clear();
    }

    void showRecords(List<GameStatusBar.HistoryRecord> records, Consumer<String> openHandler) {
        statusText.setText("AI 对局记录");
        listBox.getChildren().clear();
        for (GameStatusBar.HistoryRecord record : records) {
            Button item = new Button(record.summary());
            item.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 15));
            item.setFocusTraversable(false);
            item.setPrefWidth(500);
            item.setMinHeight(42);
            item.setWrapText(true);
            ViewStyles.battleModeButton(item, false);
            item.setOnAction(e -> openHandler.accept(record.recordId()));
            listBox.getChildren().add(item);
        }
    }

    void setRefreshHandler(Runnable handler) {
        refreshButton.setOnAction(e -> handler.run());
    }

    void setBackHandler(Runnable handler) {
        backButton.setOnAction(e -> handler.run());
    }
}
