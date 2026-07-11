package client.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

final class ResultView {
    private final VBox root = new VBox(16);
    private final Text reasonText = new Text("");
    private final Button replayButton = new Button("对局复盘");
    private final Button rematchButton = new Button("再来一局");
    private final Button returnLobbyButton = new Button("返回大厅");

    ResultView(Pane parent) {
        root.setAlignment(Pos.CENTER);
        root.setPadding(Insets.EMPTY);
        root.setStyle("-fx-background-color: transparent;");
        reasonText.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 28));
        reasonText.setFill(Color.web("#3D2B20"));
        for (Button button : new Button[]{replayButton, rematchButton, returnLobbyButton}) {
            button.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 18));
            button.setFocusTraversable(false);
            button.setPrefWidth(220);
            button.setPrefHeight(48);
            ViewStyles.roomActionButton(button);
        }
        root.getChildren().addAll(reasonText, replayButton, rematchButton, returnLobbyButton);
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

    void setReason(String text) {
        reasonText.setText(text == null ? "" : text);
    }

    void setHandlers(Runnable replay, Runnable rematch, Runnable returnLobby) {
        replayButton.setOnAction(e -> replay.run());
        rematchButton.setOnAction(e -> rematch.run());
        returnLobbyButton.setOnAction(e -> returnLobby.run());
    }
}
