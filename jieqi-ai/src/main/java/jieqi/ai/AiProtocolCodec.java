package jieqi.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Json;
import jieqi.common.Move;
import jieqi.common.PieceType;
import jieqi.rules.BoardSnapshot;
import jieqi.rules.BoardText;
import jieqi.rules.CellState;
import jieqi.rules.InitialLayout;

import java.util.Objects;
import java.util.Optional;

/**
 * JSON protocol adapter for AI clients.
 */
public final class AiProtocolCodec {

    private static final Gson GSON = new Gson();

    private AiProtocolCodec() {}

    public static AiGameState parseGameStart(String json) {
        JsonObject root = Json.parseObject(json);
        Color yourColor = Color.fromJson(requireString(root, "yourColor"));
        BoardSnapshot board = parseInitialBoard(Json.get(root, "initialBoard"));
        return new AiGameState(
                requireString(root, "redPlayerId"),
                requireString(root, "blackPlayerId"),
                yourColor,
                Json.optBool(root, "firstHand", false),
                PlayerView.of(board, yourColor));
    }

    public static String encodeMove(PlayerView view, Move move) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(move, "move");
        return encodeMove(move, view.isHidden(move.from()));
    }

    public static String encodeMove(Move move, boolean isFlip) {
        Objects.requireNonNull(move, "move");
        JsonObject root = new JsonObject();
        root.addProperty("messageType", "move");
        addMoveFields(root, move, isFlip);
        return GSON.toJson(root);
    }

    public static MoveResultMessage parseMoveResult(String json) {
        JsonObject root = Json.parseObject(json);
        JsonObject moveObject = Json.optObject(root, "move");
        if (moveObject == null) {
            throw new IllegalArgumentException("moveResult missing move object");
        }
        boolean valid = Json.optBool(root, "valid", false);
        boolean isFlip = Json.optBool(moveObject, "isFlip", false);
        Optional<PieceType> flipResult = parseOptionalPiece(root, "flipResult");
        MoveResultMessage.CapturedPiece capturedPiece = parseCapturedPiece(root);
        return new MoveResultMessage(valid, parseMoveObject(moveObject), isFlip, flipResult, capturedPiece);
    }

    private static BoardSnapshot parseInitialBoard(JsonElement initialBoard) {
        if (initialBoard == null || initialBoard.isJsonNull()) {
            return BoardText.parse(BoardText.INITIAL).board();
        }
        if (!initialBoard.isJsonArray()) {
            throw new IllegalArgumentException("initialBoard must be an array");
        }
        CellState[][] cells = new CellState[10][9];
        JsonArray cellsJson = initialBoard.getAsJsonArray();
        for (JsonElement element : cellsJson) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject cellObject = element.getAsJsonObject();
            Coord coord = parseCellCoord(cellObject);
            Color color = inferInitialColor(coord);
            boolean visible = Json.optBool(cellObject, "visible", false);
            if (visible) {
                cells[coord.rank()][coord.file()] =
                        new CellState.Revealed(color, PieceType.fromJson(requireString(cellObject, "piece")));
            } else {
                // Never trust piece on hidden cells: it is a virtual/public placeholder, not true identity.
                cells[coord.rank()][coord.file()] = new CellState.Hidden(color);
            }
        }
        return BoardSnapshot.of(cells);
    }

    private static Move parseMoveObject(JsonObject moveObject) {
        return new Move(
                parseCoord(moveObject, "fromX", "fromY"),
                parseCoord(moveObject, "toX", "toY"));
    }

    private static void addMoveFields(JsonObject root, Move move, boolean isFlip) {
        root.addProperty("fromX", fileString(move.from()));
        root.addProperty("fromY", move.from().rank());
        root.addProperty("toX", fileString(move.to()));
        root.addProperty("toY", move.to().rank());
        root.addProperty("isFlip", isFlip);
    }

    private static MoveResultMessage.CapturedPiece parseCapturedPiece(JsonObject root) {
        String raw = Json.optString(root, "capturedPiece", null);
        if (raw == null) {
            return MoveResultMessage.CapturedPiece.none();
        }
        if (raw.equalsIgnoreCase("NULL")) {
            return MoveResultMessage.CapturedPiece.unknown();
        }
        return MoveResultMessage.CapturedPiece.known(PieceType.fromJson(raw));
    }

    private static Optional<PieceType> parseOptionalPiece(JsonObject root, String key) {
        String raw = Json.optString(root, key, null);
        if (raw == null || raw.equalsIgnoreCase("NULL")) {
            return Optional.empty();
        }
        return Optional.of(PieceType.fromJson(raw));
    }

    private static Coord parseCellCoord(JsonObject cellObject) {
        String x = requireString(cellObject, "x");
        int y = Json.optInt(cellObject, "y", -1);
        return new Coord(fileOf(x), y);
    }

    private static Coord parseCoord(JsonObject object, String xKey, String yKey) {
        String x = requireString(object, xKey);
        int y = Json.optInt(object, yKey, -1);
        return new Coord(fileOf(x), y);
    }

    private static Color inferInitialColor(Coord coord) {
        if (InitialLayout.isOriginSquare(coord, Color.RED)) {
            return Color.RED;
        }
        if (InitialLayout.isOriginSquare(coord, Color.BLACK)) {
            return Color.BLACK;
        }
        throw new IllegalArgumentException("initialBoard cell is not on an origin square: " + coord);
    }

    private static String requireString(JsonObject object, String key) {
        String value = Json.optString(object, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required string field: " + key);
        }
        return value;
    }

    private static int fileOf(String x) {
        if (x == null || x.length() != 1) {
            throw new IllegalArgumentException("bad file: " + x);
        }
        int file = Character.toLowerCase(x.charAt(0)) - 'a';
        if (file < 0 || file > 8) {
            throw new IllegalArgumentException("bad file: " + x);
        }
        return file;
    }

    private static String fileString(Coord coord) {
        return Character.toString((char) ('a' + coord.file()));
    }
}
