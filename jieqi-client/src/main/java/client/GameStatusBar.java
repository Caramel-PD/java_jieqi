package client;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class GameStatusBar extends HBox {
    private final Label turnLabel = new Label("当前回合：等待连接");
    private final Label roomLabel = new Label("");
    private final Label timeoutLabel = new Label("剩余时间：--");
    private final Button readyButton = new Button("准备");
    private final Button resignButton = new Button("投降");

    public GameStatusBar() {
        setSpacing(20);
        setPadding(new Insets(12, 20, 12, 20));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #F5EDE0; -fx-border-color: #C9A86C; -fx-border-width: 0 0 2 0;");

        turnLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        roomLabel.setFont(Font.font("Microsoft YaHei", FontWeight.NORMAL, 14));
        roomLabel.setTextFill(Color.web("#5D4037"));
        timeoutLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        timeoutLabel.setTextFill(Color.DARKRED);
        readyButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        readyButton.setVisible(false);
        readyButton.setDisable(true);
        resignButton.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 14));
        resignButton.setDisable(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(turnLabel, roomLabel, timeoutLabel, spacer, readyButton, resignButton);
    }

    public void setReadyHandler(Runnable handler) {
        readyButton.setOnAction(e -> handler.run());
    }

    /** 仅在等待开局阶段显示；myReady 为 true 时按钮变为「已准备」并禁用。 */
    public void setReadyVisible(boolean visible, boolean myReady) {
        readyButton.setVisible(visible);
        if (!visible) {
            readyButton.setDisable(true);
            readyButton.setText("准备");
            return;
        }
        if (myReady) {
            readyButton.setText("已准备");
            readyButton.setDisable(true);
        } else {
            readyButton.setText("准备");
            readyButton.setDisable(false);
        }
    }

    public void updateRoom(String text) {
        if (text == null || text.isBlank()) {
            roomLabel.setText("");
            roomLabel.setVisible(false);
        } else {
            roomLabel.setText(text);
            roomLabel.setVisible(true);
        }
    }

    public void setResignHandler(Runnable handler) {
        resignButton.setOnAction(e -> handler.run());
    }

    public void setResignEnabled(boolean enabled) {
        resignButton.setDisable(!enabled);
    }

    public void updateTurn(String text) {
        turnLabel.setText(text);
    }

    public void updateTimeout(int seconds) {
        if (seconds < 0) {
            timeoutLabel.setText("剩余时间：--");
            timeoutLabel.setTextFill(Color.DARKRED);
            return;
        }
        timeoutLabel.setText("剩余时间：" + seconds + " 秒");
        if (seconds <= 10) {
            timeoutLabel.setTextFill(Color.RED);
        } else if (seconds <= 20) {
            timeoutLabel.setTextFill(Color.ORANGERED);
        } else {
            timeoutLabel.setTextFill(Color.DARKRED);
        }
    }

    public void reset() {
        turnLabel.setText("当前回合：等待连接");
        updateRoom("");
        updateTimeout(-1);
        setReadyVisible(false, false);
        setResignEnabled(false);
    }
}
