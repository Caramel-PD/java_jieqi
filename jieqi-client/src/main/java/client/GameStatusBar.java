package client;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

public class GameStatusBar extends HBox {
    private final Label turnLabel = new Label("当前回合：等待开局");
    private final Label timeoutLabel = new Label("剩余时间：--");

    public GameStatusBar() {
        setSpacing(30);
        setPadding(new Insets(12, 20, 12, 20));
        setAlignment(Pos.CENTER_LEFT);
        setStyle("-fx-background-color: #f5f0e6; -fx-border-color: #8b4513; -fx-border-width: 0 0 2 0;");

        turnLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        timeoutLabel.setFont(Font.font("Microsoft YaHei", FontWeight.BOLD, 16));
        timeoutLabel.setTextFill(Color.DARKRED);

        getChildren().addAll(turnLabel, timeoutLabel);
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
        turnLabel.setText("当前回合：等待开局");
        updateTimeout(-1);
    }
}
