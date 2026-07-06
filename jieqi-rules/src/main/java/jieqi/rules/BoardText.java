package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;

/**
 * FEN 式棋盘文本（设计文档 §10.1 "局面 FEN 式文本"）。【已实现——测试基础设施】
 *
 * <p>语法：10 行自 rank9（顶/黑）至 rank0（底/红），以 '/' 分隔；行内自 file a 至 i：
 * <ul>
 *   <li>数字 1–9 = 连续空格数；</li>
 *   <li>明子字母：K/R/N/C/P/G/B = 帅/车/马/炮/兵/仕/相（大写=红，小写=黑），对齐 §2.3 编码；</li>
 *   <li>'X'（红）/ 'x'（黑）= 暗子。<b>暗子只允许出现在暗子初始占位格上</b>
 *       （非将帅位的初始占位格，见 {@link InitialLayout#isDarkOriginSquare}）——
 *       违者解析即抛异常，防止测试用例写出规则上不可能的局面（§2.5 暗子恒在原始格）。</li>
 * </ul>
 * 末尾空格后跟行棋方 "r" / "b"。
 *
 * <p>示例（开局）：{@link #INITIAL}
 */
public final class BoardText {

    private BoardText() {}

    /** 标准开局：双王明置于 e0/e9，其余 30 子皆暗。 */
    public static final String INITIAL =
            "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/9/XXXXKXXXX r";

    public record ParsedPosition(BoardSnapshot board, Color sideToMove) {}

    public static ParsedPosition parse(String text) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length != 2) {
            throw new IllegalArgumentException("expect '<board> <r|b>': " + text);
        }
        Color side = switch (parts[1]) {
            case "r" -> Color.RED;
            case "b" -> Color.BLACK;
            default -> throw new IllegalArgumentException("side must be r|b: " + parts[1]);
        };
        String[] rows = parts[0].split("/");
        if (rows.length != 10) {
            throw new IllegalArgumentException("expect 10 rows, got " + rows.length);
        }
        CellState[][] cells = new CellState[10][9];
        for (int i = 0; i < 10; i++) {
            int rank = 9 - i;
            int file = 0;
            for (char ch : rows[i].toCharArray()) {
                if (ch >= '1' && ch <= '9') {
                    for (int k = 0; k < ch - '0'; k++) {
                        requireOnRow(file, rows[i], rank);
                        cells[rank][file++] = CellState.Empty.INSTANCE;
                    }
                } else {
                    requireOnRow(file, rows[i], rank);
                    Coord at = new Coord(file, rank);
                    cells[rank][file++] = cellOf(ch, at);
                }
            }
            if (file != 9) {
                throw new IllegalArgumentException("row for rank " + rank + " has " + file + " files: " + rows[i]);
            }
        }
        return new ParsedPosition(BoardSnapshot.of(cells), side);
    }

    /** 便捷入口：仅取棋盘（测试中行棋方通常显式传给 validate）。 */
    public static BoardSnapshot board(String text) {
        return parse(text).board();
    }

    public static String format(BoardSnapshot b, Color sideToMove) {
        StringBuilder sb = new StringBuilder();
        for (int rank = 9; rank >= 0; rank--) {
            int empties = 0;
            for (int file = 0; file < 9; file++) {
                CellState s = b.cellAt(new Coord(file, rank));
                if (s.isEmpty()) {
                    empties++;
                    continue;
                }
                if (empties > 0) {
                    sb.append(empties);
                    empties = 0;
                }
                sb.append(charOf(s));
            }
            if (empties > 0) sb.append(empties);
            if (rank > 0) sb.append('/');
        }
        sb.append(' ').append(sideToMove == Color.RED ? 'r' : 'b');
        return sb.toString();
    }

    private static void requireOnRow(int file, String row, int rank) {
        if (file > 8) {
            throw new IllegalArgumentException("row for rank " + rank + " overflows 9 files: " + row);
        }
    }

    private static CellState cellOf(char ch, Coord at) {
        if (ch == 'X' || ch == 'x') {
            if (!InitialLayout.isDarkOriginSquare(at)) {
                throw new IllegalArgumentException(
                        "hidden piece on non-origin square " + at + " (dark pieces never leave origin, see design 2.5)");
            }
            return new CellState.Hidden(ch == 'X' ? Color.RED : Color.BLACK);
        }
        Color color = Character.isUpperCase(ch) ? Color.RED : Color.BLACK;
        PieceType type = switch (Character.toUpperCase(ch)) {
            case 'K' -> PieceType.KING;
            case 'R' -> PieceType.ROOK;
            case 'N' -> PieceType.KNIGHT;
            case 'C' -> PieceType.CANNON;
            case 'P' -> PieceType.PAWN;
            case 'G' -> PieceType.GUARD;
            case 'B' -> PieceType.BISHOP;
            default -> throw new IllegalArgumentException("bad cell char: " + ch);
        };
        return new CellState.Revealed(color, type);
    }

    private static char charOf(CellState s) {
        if (s instanceof CellState.Hidden h) {
            return h.color() == Color.RED ? 'X' : 'x';
        }
        CellState.Revealed r = (CellState.Revealed) s;
        char c = switch (r.type()) {
            case KING -> 'K';
            case ROOK -> 'R';
            case KNIGHT -> 'N';
            case CANNON -> 'C';
            case PAWN -> 'P';
            case GUARD -> 'G';
            case BISHOP -> 'B';
        };
        return r.color() == Color.RED ? c : Character.toLowerCase(c);
    }
}
