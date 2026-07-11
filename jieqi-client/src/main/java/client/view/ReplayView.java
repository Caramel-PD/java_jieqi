package client.view;

import com.fasterxml.jackson.databind.JsonNode;

public final class ReplayView {
    private final GameView gameView;

    public ReplayView(GameView gameView) {
        this.gameView = gameView;
    }

    public void prepareBoard(String myColor) {
        if (myColor != null) {
            gameView.setViewForColor(myColor);
        }
        gameView.setBoardVisible(true);
        gameView.loadReplayInitialBoard();
        gameView.resetCaptures();
    }

    public void resetBoard() {
        gameView.loadReplayInitialBoard();
        gameView.resetCaptures();
    }

    public boolean applyMove(JsonNode move, String flipResult) {
        return gameView.applyReplayMoveResult(move, flipResult);
    }

    public void addMyCapture(String captured, boolean capturedRed) {
        gameView.addMyCapture(captured, capturedRed, false);
    }

    public void addOpponentCapture(String captured, boolean capturedRed, boolean capturedHidden) {
        gameView.addOpponentCapture(captured, capturedRed, capturedHidden);
    }
}
