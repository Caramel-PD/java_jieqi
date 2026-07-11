package client.view;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;

final class LobbyView {
    private final ImageView background = new ImageView();

    LobbyView(Pane parent) {
        background.setImage(loadImage("/images/lobby_background.png"));
        background.setPreserveRatio(false);
        background.setSmooth(true);
        background.fitWidthProperty().bind(parent.widthProperty());
        background.fitHeightProperty().bind(parent.heightProperty());
        background.setVisible(false);
        background.setManaged(false);
        background.addEventFilter(MouseEvent.ANY, MouseEvent::consume);
        parent.getChildren().add(background);
    }

    void setVisible(boolean visible) {
        background.setVisible(visible);
        background.setManaged(visible);
        if (visible) {
            background.toBack();
        }
    }

    private Image loadImage(String path) {
        var stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            return null;
        }
        return new Image(stream);
    }
}
