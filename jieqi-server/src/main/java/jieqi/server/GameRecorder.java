package jieqi.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class GameRecorder {
    private static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private final String roomId;
    private final String redPlayerId;
    private final String blackPlayerId;
    private final long startTime;
    private final JsonArray moves = new JsonArray();
    private long endTime;
    private String winner;
    private String winnerId;
    private String reason;
    private boolean written;

    private GameRecorder(String roomId, String redPlayerId, String blackPlayerId, long startTime) {
        this.roomId = roomId;
        this.redPlayerId = redPlayerId;
        this.blackPlayerId = blackPlayerId;
        this.startTime = startTime;
    }

    static GameRecorder start(String roomId, String redPlayerId, String blackPlayerId) {
        return new GameRecorder(roomId, redPlayerId, blackPlayerId, System.currentTimeMillis());
    }

    synchronized void recordMove(Color mover, Coord from, Coord to, boolean isFlip, boolean valid,
                                 PieceType flipResult, String capturedPiece) {
        JsonObject move = new JsonObject();
        move.addProperty("moveNo", moves.size() + 1);
        addNullable(move, "mover", mover == null ? null : mover.json());
        move.addProperty("fromX", String.valueOf((char) ('a' + from.file())));
        move.addProperty("fromY", from.rank());
        move.addProperty("toX", String.valueOf((char) ('a' + to.file())));
        move.addProperty("toY", to.rank());
        move.addProperty("isFlip", isFlip);
        move.addProperty("valid", valid);
        addNullable(move, "flipResult", flipResult == null ? null : flipResult.json());
        addNullable(move, "capturedPiece", capturedPiece);
        move.addProperty("timestamp", System.currentTimeMillis());
        moves.add(move);
    }

    synchronized void finish(String winner, String winnerId, String reason) {
        if (endTime == 0) {
            endTime = System.currentTimeMillis();
            this.winner = winner;
            this.winnerId = winnerId;
            this.reason = reason;
        }
    }

    synchronized void writeTo(Path recordsDir) throws IOException {
        if (written || recordsDir == null) {
            return;
        }
        Files.createDirectories(recordsDir);
        Files.writeString(recordsDir.resolve(roomId + ".json"), GSON.toJson(toJson()), StandardCharsets.UTF_8);
        written = true;
    }

    private JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("roomId", roomId);
        root.addProperty("redPlayerId", redPlayerId);
        root.addProperty("blackPlayerId", blackPlayerId);
        root.addProperty("startTime", startTime);
        root.addProperty("endTime", endTime);
        addNullable(root, "winner", winner);
        addNullable(root, "winnerId", winnerId);
        addNullable(root, "reason", reason);
        root.add("moves", moves.deepCopy());
        return root;
    }

    private static void addNullable(JsonObject object, String key, String value) {
        if (value == null) {
            object.add(key, JsonNull.INSTANCE);
        } else {
            object.addProperty(key, value);
        }
    }
}
