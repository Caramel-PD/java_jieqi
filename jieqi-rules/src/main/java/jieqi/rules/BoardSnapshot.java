package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;

import java.util.Arrays;

/**
 * 不可变棋盘快照（设计文档 §5.2）。【已实现——测试基础设施】
 *
 * <p>职责边界：纯存储 + 机械变换。{@link #apply} 只做"拿起—放下—翻面"的物理动作，
 * <b>不做任何合法性判断</b>（合法性属 {@link RuleEngine#validate}）、<b>不做随机抽取</b>
 * （抽池属 jieqi-server 的 HiddenPool，规则层重放棋谱时翻子类型作为入参给定 —— Q10 复盘模型）。
 */
public final class BoardSnapshot {

    /** cells[rank][file] */
    private final CellState[][] cells;

    private BoardSnapshot(CellState[][] cells) {
        this.cells = cells;
    }

    public static BoardSnapshot of(CellState[][] source) {
        CellState[][] copy = new CellState[10][9];
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                CellState s = source[r][f];
                copy[r][f] = (s == null) ? CellState.Empty.INSTANCE : s;
            }
        }
        return new BoardSnapshot(copy);
    }

    public CellState cellAt(Coord c) {
        return cells[c.rank()][c.file()];
    }

    /**
     * 机械落子：移动 from 处棋子到 to（吃掉 to 处原有棋子）；若移动的是暗子，
     * 立即翻明为 {@code flipAs} 类型（移动/吃子后立即翻开，§2.5）。
     *
     * @param flipAs 移动暗子时必须提供（服务器=HiddenPool 抽取结果；复盘=棋谱记录值）；
     *               移动明子时忽略，可传 null
     * @return 新快照（本对象不变）
     * @throws IllegalArgumentException from 为空格 / from==to / 暗子未给 flipAs
     */
    public BoardSnapshot apply(Coord from, Coord to, PieceType flipAs) {
        if (from.equals(to)) {
            throw new IllegalArgumentException("from==to (no in-place flip, Q6/Q11): " + from);
        }
        CellState moving = cellAt(from);
        if (moving.isEmpty()) {
            throw new IllegalArgumentException("no piece at " + from);
        }
        CellState landed;
        if (moving instanceof CellState.Hidden h) {
            if (flipAs == null) {
                throw new IllegalArgumentException("moving a hidden piece requires flipAs type");
            }
            landed = new CellState.Revealed(h.color(), flipAs);
        } else {
            landed = moving;
        }
        CellState[][] next = new CellState[10][9];
        for (int r = 0; r < 10; r++) {
            next[r] = Arrays.copyOf(cells[r], 9);
        }
        next[from.rank()][from.file()] = CellState.Empty.INSTANCE;
        next[to.rank()][to.file()] = landed;
        return new BoardSnapshot(next);
    }

    public int countPieces(Color side) {
        int n = 0;
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                CellState s = cells[r][f];
                if (!s.isEmpty() && s.color() == side) n++;
            }
        }
        return n;
    }

    public int countHidden(Color side) {
        int n = 0;
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                if (cells[r][f] instanceof CellState.Hidden h && h.color() == side) n++;
            }
        }
        return n;
    }
}
