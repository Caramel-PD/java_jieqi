package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【判例测试·红灯】RepetitionTracker.onMoveApplied（设计文档 §5.2 签名的包装）集成测试：
 * 真实棋盘 + 机械 apply，事实（将军 / 哈希 / 捉）由实现内部推导。
 * 依赖 P0-4(givesCheck) 与 P0-6/7 全部就绪后转绿——这是 P0 阶段的"收官测试"。
 */
class RepetitionIntegrationTest {

    /**
     * 局面：黑将 e9、红帅 e0、红兵 e4（将门线永久屏障）、红车 a1。
     * 脚本 13 半步：红车 a1 上九路后 a9/a8 穿梭，连将 7 次；黑将 e9/e8 穿梭应将。
     * 每步均为真实合法着法（无照面：e 线有 e4 屏障；黑应将自愿——Q2 不强制，但应将合法）。
     */
    static final String BOARD = "4k4/9/9/9/9/4P4/9/9/R8/4K4 r";

    static final String[] SCRIPT = {
            "a1a9", "e9e8",
            "a9a8", "e8e9",
            "a8a9", "e9e8",
            "a9a8", "e8e9",
            "a8a9", "e9e8",
            "a9a8", "e8e9",
            "a8a9"                      // 第 13 半步 = 红方第 7 次连续将军
    };

    @Test
    @DisplayName("真实棋盘长将 7 次：第 13 半步返回 REPETITION_LOSS，此前均 NONE")
    void perpetualCheckOnRealBoard() {
        RepetitionTracker tracker = new RepetitionTracker();
        BoardSnapshot board = BoardText.board(BOARD);
        Color mover = Color.RED;

        for (int i = 0; i < SCRIPT.length; i++) {
            Move m = Move.parse(SCRIPT[i]);
            assertTrue(RuleEngine.validate(board, mover, m.from(), m.to()).legal(),
                    "脚本第 " + (i + 1) + " 半步 " + m + " 必须合法");
            BoardSnapshot after = board.apply(m.from(), m.to(), null);   // 全明子局面，无翻子
            RepetitionVerdict v = tracker.onMoveApplied(board, after, mover, m.from(), m.to(), false);
            if (i < SCRIPT.length - 1) {
                assertEquals(RepetitionVerdict.NONE, v, "第 " + (i + 1) + " 半步不应触发判罚");
            } else {
                assertEquals(RepetitionVerdict.REPETITION_LOSS, v,
                        "第 7 次连续将军且局面重复 => 长将判负（Q4，实施方=红）");
            }
            board = after;
            mover = mover.opposite();
        }
    }
}
