package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;
import jieqi.rules.RepetitionTracker.MoveFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static jieqi.common.Color.*;
import static jieqi.common.PieceType.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 【判例测试·红灯】RepetitionTracker.onFacts 纯状态机（设计文档 §2.10；附录 B 判例 19–24）。
 *
 * <p>测试方法论：本类<b>不摆棋盘</b>——直接投喂"事实脚本"（谁走、哈希、是否将/捉/吃/翻），
 * 把状态机与棋盘几何解耦，全分支可精确制导。哈希值为任意常数，仅相等性有意义。
 * 缺省阈值：连续 &gt;6（即第 7 次）且序列内同局面 ≥3 次；80 半步无吃子（§11.3 配置表）。
 */
class RepetitionTrackerTest {

    /** 事实速记。chase=null 表示本步无捉。 */
    private static MoveFacts f(Color mover, String from, String to, long hashAfter,
                               boolean capture, boolean flip, boolean check,
                               String chase, PieceType typeAfter) {
        return new MoveFacts(mover, Coord.parse(from), Coord.parse(to), hashAfter,
                capture, flip, check, chase == null ? null : Coord.parse(chase), typeAfter);
    }

    // ---------- 长将（判例 21 / 22 / 24；Q4 / Q5）----------

    /**
     * 车长将脚本：红车 a9/a8 横线连将，黑将 e9/e8 穿梭应将。
     * 红方走后局面两态循环：HA=(车a9,将e9)、HC=(车a8,将e8)。
     * 第 7 次连将时 HA 已第 4 次出现（第1/3/5/7次将后），streak=7>6 且重复≥3 => 判负。
     */
    @Test
    @DisplayName("B21_车长将：第 7 次连续将军且局面重复 => 实施方判负（Q4）")
    void perpetualCheckByRook() {
        RepetitionTracker t = new RepetitionTracker();
        long HA = 101, HC = 102, HB = 201, HD = 202;
        // 红车 a1 上九路后在 a9<->a8 穿梭连将；红走后局面两态：HA=(车a9,将e9)、HC=(车a8,将e8)
        String[][] red = {{"a1", "a9"}, {"a9", "a8"}, {"a8", "a9"}, {"a9", "a8"}, {"a8", "a9"}, {"a9", "a8"}};
        long[] redHash = {HA, HC, HA, HC, HA, HC};
        for (int i = 0; i < 6; i++) {
            boolean odd = i % 2 == 0;
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED,
                    red[i][0], red[i][1], redHash[i], false, false, true, null, ROOK)),
                    "第 " + (i + 1) + " 次将军尚未到阈值");
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK,
                    odd ? "e9" : "e8", odd ? "e8" : "e9",
                    odd ? HB : HD, false, false, false, null, KING)));
        }
        assertEquals(RepetitionVerdict.REPETITION_LOSS,
                t.onFacts(f(RED, "a8", "a9", HA, false, false, true, null, ROOK)),
                "第 7 次连续将军，且局面 HA 第 4 次出现（>=3）=> repetition_loss（红负）");
    }

    @Test
    @DisplayName("B22_中断清零：5 连将后走一步闲着，续将不判罚（Q4 必须连续）")
    void interruptionResetsStreak() {
        RepetitionTracker t = new RepetitionTracker();
        long HA = 101, HC = 102;
        for (int round = 1; round <= 5; round++) {
            boolean odd = round % 2 == 1;
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED, odd ? "a9" : "a8", odd ? "a8" : "a9",
                    odd ? HC : HA, false, false, true, null, ROOK)));
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK, odd ? "e9" : "e8", odd ? "e8" : "e9",
                    200 + round, false, false, false, null, KING)));
        }
        // 闲着（非将非捉）——中断，计数清零（Q4）
        assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED, "a8", "c8", 301, false, false, false, null, ROOK)));
        assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK, "e9", "e8", 302, false, false, false, null, KING)));
        // 续将 3 次：累计第 6/7/8 次，但连续仅 1/2/3 次 => 均不判罚
        long[] hs = {HA, HC, HA};
        String[][] mv = {{"c8", "a8"}, {"a8", "a9"}, {"a9", "a8"}};
        for (int i = 0; i < 3; i++) {
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED, mv[i][0], mv[i][1], hs[i],
                    false, false, true, null, ROOK)), "中断后重新累计，第 " + (i + 1) + " 次");
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK, i % 2 == 0 ? "e8" : "e9",
                    i % 2 == 0 ? "e9" : "e8", 400 + i, false, false, false, null, KING)));
        }
    }

    @Test
    @DisplayName("B24_兵长将同样判负（Q5：兵卒长将 => 负，不适用兵卒和棋特例）")
    void perpetualCheckByPawnLoses() {
        RepetitionTracker t = new RepetitionTracker();
        long HA = 111, HC = 112;
        for (int round = 1; round <= 6; round++) {
            boolean odd = round % 2 == 1;
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED,
                    odd ? "e8" : "d8", odd ? "d8" : "e8", odd ? HA : HC,
                    false, false, true, null, PAWN)));
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK,
                    odd ? "d9" : "e9", odd ? "e9" : "d9", 210 + (odd ? 1 : 2),
                    false, false, false, null, KING)));
        }
        assertEquals(RepetitionVerdict.REPETITION_LOSS,
                t.onFacts(f(RED, "e8", "d8", HA, false, false, true, null, PAWN)));
    }

    // ---------- 长捉（判例 23 + 非兵卒对照；Q5）----------

    /**
     * 兵长捉脚本：红兵 d6/e6 横向穿梭，始终捉黑车（黑车 d7/e7 穿梭逃）。
     * 目标同一性：黑车每逃一步（from=当前被捉格），跟踪目标随之更新到 to（同一枚子）。
     */
    @Test
    @DisplayName("B23_兵长捉任何子：第 7 次连捉且重复 => 判和（Q5 兵卒特例）")
    void perpetualChaseByPawnDraws() {
        RepetitionTracker t = new RepetitionTracker();
        long A1 = 401, A2 = 402;
        for (int round = 1; round <= 6; round++) {
            boolean odd = round % 2 == 1;
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED,
                    odd ? "e6" : "d6", odd ? "d6" : "e6", odd ? A1 : A2,
                    false, false, false, odd ? "d7" : "e7", PAWN)), "第 " + round + " 次捉");
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK,
                    odd ? "d7" : "e7", odd ? "e7" : "d7", 500 + round,
                    false, false, false, null, ROOK)), "黑车逃跑 => 跟踪目标更新");
        }
        assertEquals(RepetitionVerdict.REPETITION_DRAW,
                t.onFacts(f(RED, "e6", "d6", A1, false, false, false, "d7", PAWN)),
                "兵卒长捉任何子 => repetition_draw");
    }

    @Test
    @DisplayName("非兵卒长捉：同脚本换成车 => 实施方判负（Q5）")
    void perpetualChaseByRookLoses() {
        RepetitionTracker t = new RepetitionTracker();
        long A1 = 411, A2 = 412;
        for (int round = 1; round <= 6; round++) {
            boolean odd = round % 2 == 1;
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED,
                    odd ? "c6" : "b6", odd ? "b6" : "c6", odd ? A1 : A2,
                    false, false, false, odd ? "b8" : "c8", ROOK)));
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK,
                    odd ? "b8" : "c8", odd ? "c8" : "b8", 520 + round,
                    false, false, false, null, KNIGHT)));
        }
        assertEquals(RepetitionVerdict.REPETITION_LOSS,
                t.onFacts(f(RED, "c6", "b6", A1, false, false, false, "b8", ROOK)));
    }

    @Test
    @DisplayName("换捉目标 => 连续性断裂，streak 重置为 1，不判罚（§2.10 状态机）")
    void switchingChaseTargetResets() {
        RepetitionTracker t = new RepetitionTracker();
        for (int round = 1; round <= 4; round++) {
            boolean odd = round % 2 == 1;
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED,
                    odd ? "c6" : "b6", odd ? "b6" : "c6", 430 + (odd ? 1 : 2),
                    false, false, false, odd ? "b8" : "c8", ROOK)));
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK,
                    odd ? "b8" : "c8", odd ? "c8" : "b8", 540 + round,
                    false, false, false, null, KNIGHT)));
        }
        // 第 5 步改捉另一枚子（目标格 i9 与跟踪中的目标无关）=> streak 从 1 重新累计
        for (int k = 0; k < 4; k++) {
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED, "b6", "c6", 451 + (k % 2),
                    false, false, false, "i9", ROOK)), "换目标后第 " + (k + 1) + " 次，远未到阈值");
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK, "e9", "e8", 560 + k,
                    false, false, false, null, KING)));
        }
    }

    // ---------- 80 半步无吃子（判例 19 / 20；Q3）----------

    @Test
    @DisplayName("B19_80 半步（40 回合）无吃子 => 判和；翻子不算吃子不重置（Q3）")
    void eightyHalfMovesNoCapture() {
        RepetitionTracker t = new RepetitionTracker();
        for (int i = 1; i <= 80; i++) {
            Color mover = (i % 2 == 1) ? RED : BLACK;
            boolean flip = (i % 3 == 0);   // 周期性翻子：Q3 明确翻子不重置计数
            RepetitionVerdict v = t.onFacts(f(mover, "a1", "a2", 1000 + i,
                    false, flip, false, null, ROOK));
            if (i < 80) {
                assertEquals(RepetitionVerdict.NONE, v, "第 " + i + " 半步不应判和");
            } else {
                assertEquals(RepetitionVerdict.DRAW_NO_CAPTURE, v, "第 80 半步判和");
            }
        }
        assertEquals(80, t.noCaptureHalfMoves());
    }

    @Test
    @DisplayName("B20_第 79 半步吃子 => 计数清零，第 80 半步不判和")
    void captureResetsCounter() {
        RepetitionTracker t = new RepetitionTracker();
        for (int i = 1; i <= 78; i++) {
            assertEquals(RepetitionVerdict.NONE, t.onFacts(f((i % 2 == 1) ? RED : BLACK,
                    "a1", "a2", 2000 + i, false, false, false, null, ROOK)));
        }
        assertEquals(RepetitionVerdict.NONE, t.onFacts(f(RED, "a1", "b1", 2079,
                true, false, false, null, ROOK)), "第 79 半步吃子");
        assertEquals(RepetitionVerdict.NONE, t.onFacts(f(BLACK, "e9", "e8", 2080,
                false, false, false, null, KING)), "第 80 半步：距上次吃子仅 1 半步");
        assertEquals(1, t.noCaptureHalfMoves(), "吃子步清零后，其后第一个非吃子半步计 1");
    }
}
