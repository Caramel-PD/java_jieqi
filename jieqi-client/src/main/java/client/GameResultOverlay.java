package client;

import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

public class GameResultOverlay extends StackPane {
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
