package jieqi.rules;

import jieqi.common.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【判例测试·红灯】givesCheck —— §2.10 "将军步"操作性定义：
 * 一方走子后，其任一棋子按当前身份可于下一步吃到对方将帅。
 * 本判定是 RepetitionTracker 长将累计的输入事实。
 */
class CheckDetectionTest {

    @Test
    @DisplayName("车同线无阻隔 => 将军")
    void rookCheck() {
        BoardSnapshot b = BoardText.board("3k5/9/3R5/9/9/9/9/9/9/4K4 r");
        assertTrue(RuleEngine.givesCheck(b, Color.RED));
    }

    @Test
    @DisplayName("车不同线 => 非将军")
    void rookNoCheck() {
        BoardSnapshot b = BoardText.board("3k5/9/4R4/9/9/9/9/9/9/4K4 r");
        assertFalse(RuleEngine.givesCheck(b, Color.RED));
    }

    @Test
    @DisplayName("炮隔单架对将线 => 将军；零架 => 非将军")
    void cannonCheck() {
        assertTrue(RuleEngine.givesCheck(
                BoardText.board("3k5/9/9/9/9/3P5/9/9/9/3C1K3 r"), Color.RED), "d0 炮隔 d4 架打 d9");
        assertFalse(RuleEngine.givesCheck(
                BoardText.board("3k5/9/9/9/9/9/9/9/9/3C1K3 r"), Color.RED), "零架吃不到将");
    }

    @Test
    @DisplayName("马日字踩将（马腿空）=> 将军")
    void knightCheck() {
        assertTrue(RuleEngine.givesCheck(
                BoardText.board("3k5/9/2N6/9/9/9/9/9/9/4K4 r"), Color.RED), "c7 马踩 d9，腿 c8 空");
    }
}
