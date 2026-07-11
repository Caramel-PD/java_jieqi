package client.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

final class MatchView {
    private final ImageView background = new ImageView();
    private final VBox controls = new VBox(18);
    private final Text statusText = new Text("匹配中……");
    private final Button cancelButton = new Button("取消匹配");
    private final PregameView pregameView;

    MatchView(Pane parent) {
        background.setImage(loadImage("/images/post_match_background.png"));
        background.setPreserveRatio(false);
        background.setSmooth(true);
        background.setOpacity(0.78);
        background.fitWidthProperty().bind(parent.widthProperty());
        background.fitHeightProperty().bind(parent.heightProperty());
        background.setVisible(false);
        background.setManaged(false);
        background.addEventFilter(MouseEvent.ANY, MouseEvent::consume);
        parent.getChildren().add(background);

        statusText.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 30));
        statusText.setFill(Color.web("#3D2B20"));
        cancelButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 18));
        cancelButton.setFocusTraversable(false);
        cancelButton.setPrefWidth(220);
        cancelButton.setPrefHeight(48);
        ViewStyles.roomActionButton(cancelButton);

        controls.setAlignment(Pos.CENTER);
        controls.getChildren().addAll(statusText, cancelButton);
        controls.layoutXProperty().bind(parent.widthProperty().subtract(controls.widthProperty()).divide(2));
        controls.layoutYProperty().bind(parent.heightProperty().subtract(controls.heightProperty()).divide(2));
        controls.setVisible(false);
        controls.setManaged(false);
        parent.getChildren().add(controls);
        pregameView = new PregameView(parent);
    }

    void setBackgroundVisible(boolean visible) {
        background.setVisible(visible);
        background.setManaged(visible);
        if (visible) {
            background.toBack();
        }
    }

    void setMatchingVisible(boolean visible) {
        setBackgroundVisible(visible);
        controls.setVisible(visible);
        controls.setManaged(visible);
        if (visible) {
            controls.toFront();
        }
    }

    void setStatus(String text, boolean canCancel) {
        statusText.setText(text);
        cancelButton.setVisible(canCancel);
        cancelButton.setManaged(canCancel);
    }

    void setCancelHandler(Runnable handler) {
        cancelButton.setOnAction(e -> handler.run());
    }


    void setRoomWaitingVisible(boolean visible) {
        pregameView.setRoomWaitingVisible(visible);
    }

    void setStartMatchHandler(Runnable handler) {
        pregameView.setStartMatchHandler(handler);
    }

    void setAiRecordsHandler(Runnable handler) {
        pregameView.setAiRecordsHandler(handler);
    }

    String selectedBattleMode() {
        return pregameView.selectedBattleMode();
    }

    void setFirstHandHandlers(Runnable wantFirst, Runnable declineFirst) {
        pregameView.setFirstHandHandlers(wantFirst, declineFirst);
    }

    void setReadyHandler(Runnable handler) {
        pregameView.setReadyHandler(handler);
    }

    void updatePregameControls(boolean showStart, boolean showCancel,
                               boolean showFirstHand, boolean showReady,
                               boolean myReady) {
        pregameView.update(showStart, showCancel, showFirstHand, showReady, myReady);
    }
    private Image loadImage(String path) {
        var stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            return null;
        }
        return new Image(stream);
    }
}


final class PregameView {
    private final VBox root = new VBox(10);
    private final VBox battleModeControls = new VBox(12);
    private final Text roomStatusText = new Text("等待对手准备……");
    private final Button playerBattleButton = new Button("玩家 vs 玩家");
    private final Button playerAiBattleButton = new Button("玩家 vs AI");
    private final Button aiBattleButton = new Button("AI vs AI");
    private final Button startMatchButton = new Button("开始匹配");
    private final Button aiRecordsButton = new Button("查看AI对局");
    private final Button wantFirstButton = new Button("我要先手");
    private final Button declineFirstButton = new Button("不抢先手");
    private final Button readyButton = new Button("准备");

    private String selectedBattleMode;

    PregameView(Pane parent) {
        root.setPadding(Insets.EMPTY);
        root.setAlignment(Pos.CENTER);
        root.setStyle("-fx-background-color: transparent;");
        battleModeControls.setAlignment(Pos.CENTER);

        roomStatusText.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 30));
        roomStatusText.setFill(Color.web("#3D2B20"));
        roomStatusText.setVisible(false);
        roomStatusText.setManaged(false);

        for (Button button : new Button[]{startMatchButton, wantFirstButton, declineFirstButton, readyButton,
                playerBattleButton, playerAiBattleButton, aiBattleButton, aiRecordsButton}) {
            button.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 18));
            button.setFocusTraversable(false);
            button.setPrefWidth(220);
            button.setPrefHeight(48);
        }
        for (Button button : new Button[]{wantFirstButton, declineFirstButton, readyButton,
                startMatchButton, aiRecordsButton}) {
            ViewStyles.roomActionButton(button);
        }

        playerBattleButton.setOnAction(e -> selectBattleMode("player"));
        playerAiBattleButton.setOnAction(e -> selectBattleMode("player_ai"));
        aiBattleButton.setOnAction(e -> selectBattleMode("ai"));
        updateBattleModeStyles();
        updateBattleActionButtons();

        battleModeControls.getChildren().addAll(playerBattleButton, playerAiBattleButton,
                startMatchButton, aiBattleButton, aiRecordsButton);
        root.getChildren().addAll(battleModeControls, roomStatusText,
                wantFirstButton, declineFirstButton, readyButton);
        root.layoutXProperty().bind(parent.widthProperty().subtract(root.widthProperty()).divide(2));
        root.layoutYProperty().bind(parent.heightProperty().subtract(root.heightProperty()).divide(2));
        parent.getChildren().add(root);
        update(false, false, false, false, false);
    }

    String selectedBattleMode() {
        return selectedBattleMode;
    }

    void setStartMatchHandler(Runnable handler) {
        startMatchButton.setOnAction(e -> handler.run());
    }

    void setAiRecordsHandler(Runnable handler) {
        aiRecordsButton.setOnAction(e -> handler.run());
    }

    void setFirstHandHandlers(Runnable wantFirst, Runnable declineFirst) {
        wantFirstButton.setOnAction(e -> wantFirst.run());
        declineFirstButton.setOnAction(e -> declineFirst.run());
    }

    void setReadyHandler(Runnable handler) {
        readyButton.setOnAction(e -> handler.run());
    }

    void setRoomWaitingVisible(boolean visible) {
        roomStatusText.setVisible(visible);
        roomStatusText.setManaged(visible);
    }

    void update(boolean showStart, boolean showCancel, boolean showFirstHand, boolean showReady, boolean myReady) {
        boolean showBattleModes = showStart;
        battleModeControls.setVisible(showBattleModes);
        battleModeControls.setManaged(showBattleModes);
        updateBattleActionButtons();
        wantFirstButton.setVisible(showFirstHand);
        wantFirstButton.setManaged(showFirstHand);
        declineFirstButton.setVisible(showFirstHand);
        declineFirstButton.setManaged(showFirstHand);
        readyButton.setVisible(showReady);
        readyButton.setManaged(showReady);
        readyButton.setText(myReady ? "已准备" : "准备");
        readyButton.setDisable(myReady);
        root.setVisible(showStart || showFirstHand || showReady || roomStatusText.isVisible());
        root.setManaged(root.isVisible());
        if (root.isVisible()) {
            root.toFront();
        }
    }

    private void selectBattleMode(String mode) {
        selectedBattleMode = mode;
        updateBattleModeStyles();
        updateBattleActionButtons();
    }

    private void updateBattleModeStyles() {
        ViewStyles.battleModeButton(playerBattleButton, "player".equals(selectedBattleMode));
        ViewStyles.battleModeButton(playerAiBattleButton, "player_ai".equals(selectedBattleMode));
        ViewStyles.battleModeButton(aiBattleButton, "ai".equals(selectedBattleMode));
        ViewStyles.roomActionButton(startMatchButton);
        ViewStyles.roomActionButton(aiRecordsButton);
    }

    private void updateBattleActionButtons() {
        boolean canStartMatch = "player".equals(selectedBattleMode) || "player_ai".equals(selectedBattleMode);
        startMatchButton.setDisable(!canStartMatch);
        aiRecordsButton.setDisable(!"ai".equals(selectedBattleMode));
    }
}
