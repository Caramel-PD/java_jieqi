package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;

import java.util.Random;

/**
 * 局面 Zobrist 哈希（设计文档 §2.10 / §5.3(d)）。【已实现——测试基础设施】
 *
 * <p>键空间约定（与 §2.10 一字不差）：每格 (颜色 × {7 种明子类型 + 1 个暗子标记}) + 行棋方。
 * <b>暗子的虚拟类型不入哈希</b>——暗子恒在原始格，同格虚拟类型恒定，入哈希是冗余。
 *
 * <p>固定种子保证跨进程确定性（复盘、日志比对、测试可复现）。AI 置换表若需把双池构成折入键
 * （设计文档 §8.4），在 jieqi-ai 侧另行叠加，不改本类。
 */
public final class ZobristHash {

    private ZobristHash() {}

    /** [rank][file][stateIndex]；stateIndex = colorOrdinal*8 + (hidden ? 7 : type.code)。 */
    private static final long[][][] KEYS = new long[10][9][16];
    private static final long SIDE_BLACK_KEY;

    static {
        Random rng = new Random(0x5EC1_2026_0705L);
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                for (int s = 0; s < 16; s++) {
                    KEYS[r][f][s] = rng.nextLong();
                }
            }
        }
        SIDE_BLACK_KEY = rng.nextLong();
    }

    public static long of(BoardSnapshot board, Color sideToMove) {
        long h = 0L;
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                CellState s = board.cellAt(new Coord(f, r));
                if (s.isEmpty()) continue;
                int idx;
                if (s instanceof CellState.Hidden hid) {
                    idx = hid.color().ordinal() * 8 + 7;
                } else {
                    CellState.Revealed rev = (CellState.Revealed) s;
                    idx = rev.color().ordinal() * 8 + rev.type().code;
                }
                h ^= KEYS[r][f][idx];
            }
        }
        if (sideToMove == Color.BLACK) {
            h ^= SIDE_BLACK_KEY;
        }
        return h;
    }
}
