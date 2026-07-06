package jieqi.common;

/**
 * 棋盘坐标（设计文档 §2.1）：
 * 列 file = 0..8 对应 'a'..'i'（左→右）；行 rank = 0..9（下→上）。
 * 红方在下（rank 0 侧、先手），黑方在上（rank 9 侧）。
 * 文本形式如 "a0"（红左下角车位）、"e0"（红帅）、"i9"（黑右上角车位）。
 */
public record Coord(int file, int rank) {

    public Coord {
        if (file < 0 || file > 8 || rank < 0 || rank > 9) {
            throw new IllegalArgumentException("coord out of board: file=" + file + ", rank=" + rank);
        }
    }

    /** 解析 "a0".."i9"。 */
    public static Coord parse(String s) {
        if (s == null || s.length() != 2) {
            throw new IllegalArgumentException("bad coord: " + s);
        }
        int f = s.charAt(0) - 'a';
        int r = s.charAt(1) - '0';
        return new Coord(f, r);
    }

    /** 是否在 side 方九宫内（files d..f；红 ranks 0..2，黑 ranks 7..9）。 */
    public boolean inPalace(Color side) {
        if (file < 3 || file > 5) return false;
        return side == Color.RED ? rank <= 2 : rank >= 7;
    }

    /** side 方棋子位于本坐标时是否已过河（红 rank>=5，黑 rank<=4）。 */
    public boolean crossedRiver(Color side) {
        return side == Color.RED ? rank >= 5 : rank <= 4;
    }

    /** side 方"向前"一格的 rank 增量（红 +1，黑 -1）。 */
    public static int forward(Color side) {
        return side == Color.RED ? 1 : -1;
    }

    @Override
    public String toString() {
        return "" + (char) ('a' + file) + (char) ('0' + rank);
    }
}
