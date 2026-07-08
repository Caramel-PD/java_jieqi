package client;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChessBoard extends Pane {
    private static final int CELL = 70;
    private static final int OFFSET_X = 50;
    private static final int OFFSET_Y = 50;

    private final Map<String, ChessPiece> pieceMap = new HashMap<>();
    private final Map<String, Rectangle> hitAreas = new HashMap<>();
    private final List<int[]> palaceDiagonals = new ArrayList<>();
    private final List<Line> palaceLines = new ArrayList<>();

    private String selectedPos = null;
    private GameController controller;
    private boolean gameOver = false;
    private boolean flipped = false;

    public ChessBoard(GameController controller) {
        this.controller = controller;
        drawBoard();
        createCellHitAreas();
    }

    public void setController(GameController controller) {
        this.controller = controller;
    }

    /** 黑方视角：己方棋子显示在屏幕下方（仅影响显示，协议坐标不变）。 */
    public void setViewForColor(String myColor) {
        flipped = "black".equals(myColor);
        refreshPalaceLines();
        hitAreas.forEach((key, hit) -> {
            int file = key.charAt(0) - 'a';
            int rank = Integer.parseInt(key.substring(1));
            layoutHitArea(hit, file, rank);
        });
        pieceMap.values().forEach(ChessPiece::updatePosition);
    }

    public void setGameOver(boolean over) {
        this.gameOver = over;
        if (over) {
            pieceMap.values().forEach(p -> p.setHighlight(false));
            selectedPos = null;
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
        for (int r = 0; r < 10; r++) {
            Line line = new Line(OFFSET_X, OFFSET_Y + r * CELL,
                                 OFFSET_X + 8 * CELL, OFFSET_Y + r * CELL);
            line.setStroke(Color.BLACK);
            getChildren().add(line);
        }
        for (int f = 0; f < 9; f++) {
            double x = OFFSET_X + f * CELL;
            if (f > 0 && f < 8) {
                Line top = new Line(x, OFFSET_Y, x, OFFSET_Y + 4 * CELL);
                top.setStroke(Color.BLACK);
                getChildren().add(top);
                Line bottom = new Line(x, OFFSET_Y + 5 * CELL, x, OFFSET_Y + 9 * CELL);
                bottom.setStroke(Color.BLACK);
                getChildren().add(bottom);
            } else {
                Line line = new Line(x, OFFSET_Y, x, OFFSET_Y + 9 * CELL);
                line.setStroke(Color.BLACK);
                getChildren().add(line);
            }
        }
        Text river = new Text(OFFSET_X + 200, OFFSET_Y + 330, "楚河        汉界");
        river.setStyle("-fx-font-size:25");
        getChildren().add(river);

        palaceDiagonals.clear();
        palaceLines.clear();
        addPalaceDiagonal(3, 0, 5, 2);
        addPalaceDiagonal(5, 0, 3, 2);
        addPalaceDiagonal(3, 7, 5, 9);
        addPalaceDiagonal(5, 7, 3, 9);
    }

    private void addPalaceDiagonal(int f1, int r1, int f2, int r2) {
        Line line = new Line();
        line.setStroke(Color.BLACK);
        palaceDiagonals.add(new int[]{f1, r1, f2, r2});
        palaceLines.add(line);
        getChildren().add(line);
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
                getChildren().add(0, hit);
            }
        }
    }

    private void layoutHitArea(Rectangle hit, int file, int rank) {
        hit.relocate(cellCenterX(file) - CELL / 2.0, cellCenterY(rank) - CELL / 2.0);
    }

    private void handleCellClick(String key) {
        if (gameOver || controller == null || selectedPos == null) return;
        if (!controller.isMyTurn()) return;
        if (pieceMap.containsKey(key)) return;

        int file = key.charAt(0) - 'a';
        int rank = Integer.parseInt(key.substring(1));

        ChessPiece from = pieceMap.get(selectedPos);
        if (from == null) {
            selectedPos = null;
            return;
        }
        if (!controller.isMyPiece(from.getRed())) return;

        controller.sendMove(from.getFileLetter(), from.getRank(),
                            String.valueOf((char) ('a' + file)), rank, false);
        from.setHighlight(false);
        selectedPos = null;
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
    }

    private void addPiece(ChessPiece p) {
        p.setOnMouseClicked(e -> handlePieceClick(p));
        getChildren().add(p);
        String key = p.getFileLetter() + p.getRank();
        pieceMap.put(key, p);
    }

    private void removePiece(String key) {
        ChessPiece p = pieceMap.remove(key);
        if (p != null) {
            getChildren().remove(p);
        }
    }

    private void clearPieces() {
        getChildren().removeIf(node -> node instanceof ChessPiece);
        pieceMap.clear();
        selectedPos = null;
    }

    private void handlePieceClick(ChessPiece clicked) {
        if (gameOver || controller == null) return;
        if (!controller.isMyTurn()) return;

        String pos = clicked.getFileLetter() + clicked.getRank();
        if (selectedPos == null) {
            if (!controller.isMyPiece(clicked.getRed())) return;
            selectedPos = pos;
            clicked.setHighlight(true);
        } else {
            ChessPiece from = pieceMap.get(selectedPos);
            if (from == null) {
                selectedPos = null;
                return;
            }
            if (selectedPos.equals(pos)) {
                clicked.setHighlight(false);
                selectedPos = null;
                return;
            }
            if (!controller.isMyPiece(from.getRed())) {
                from.setHighlight(false);
                selectedPos = null;
                return;
            }
            controller.sendMove(from.getFileLetter(), from.getRank(),
                                clicked.getFileLetter(), clicked.getRank(), false);
            from.setHighlight(false);
            selectedPos = null;
        }
    }

    /** 开局根据所在半场推断颜色；走子后沿用棋子自身颜色。 */
    private boolean inferPieceColor(int rank) {
        return rank <= 4;
    }
}
