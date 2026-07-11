package client.model;

public class BoardCell {
    public String x;        // "a"-"i"
    public int y;           // 0-9
    public String piece;    // "rook", "king", ...
    public boolean visible; // true=明子

    public BoardCell() {}
    public BoardCell(String x, int y, String piece, boolean visible) {
        this.x = x;
        this.y = y;
        this.piece = piece;
        this.visible = visible;
    }
}
