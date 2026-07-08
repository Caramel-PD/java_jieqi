package client;

import javafx.animation.RotateTransition;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.util.Duration;

public class ChessPiece extends ImageView {
    private final ChessBoard board;
    private int file;      // 0-8
    private int rank;      // 0-9（协议y值）
    private boolean red;
    private boolean hidden;
    private PieceType type;

    public ChessPiece(ChessBoard board, PieceType type, boolean red, int file, int rank, boolean visible) {
        this.board = board;
        this.type = type;
        this.red = red;
        this.file = file;
        this.rank = rank;
        this.hidden = !visible;
        setFitWidth(90);
        setFitHeight(90);
        if (visible) {
            showRealPiece();
        } else {
            showCover();
        }
        updatePosition();
    }

    public void reveal() {
        if (!hidden) return;
        hidden = false;
        RotateTransition rt = new RotateTransition(Duration.millis(300), this);
        rt.setToAngle(90);
        rt.setOnFinished(e -> {
            showRealPiece();
            setRotate(270);
            RotateTransition back = new RotateTransition(Duration.millis(300), this);
            back.setToAngle(360);
            back.setOnFinished(ev -> setRotate(0));
            back.play();
        });
        rt.play();
    }

    private void showCover() {
        setImage(load("/pieces/cover.png"));
    }

    private void showRealPiece() {
        String prefix = red ? "red_" : "black_";
        String path = "/pieces/" + prefix + type.value() + ".png";
        setImage(load(path));
    }

    public void updatePosition() {
        board.layoutPiece(this);
    }

    private Image load(String path) {
        var stream = getClass().getResourceAsStream(path);
        if (stream == null) {
            System.err.println("棋子图片缺失: " + path);
            return null;
        }
        return new Image(stream);
    }

    // Getters/Setters
    public int getFile() { return file; }
    public int getRank() { return rank; }
    public String getFileLetter() { return String.valueOf((char)('a' + file)); }
    public void setFile(int file) { this.file = file; }
    public void setRank(int rank) { this.rank = rank; }
    public void setType(PieceType type) { this.type = type; }
    public boolean getRed() { return red; }
    public boolean isHidden() { return hidden; }
    public void setHighlight(boolean on) {
        if (on) {
            DropShadow glow = new DropShadow();
            glow.setColor(Color.GOLD);
            glow.setRadius(16);
            glow.setSpread(0.45);
            setEffect(glow);
            setScaleX(1.06);
            setScaleY(1.06);
        } else {
            setEffect(null);
            setScaleX(1.0);
            setScaleY(1.0);
        }
    }
}