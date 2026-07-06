package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【判例测试·红灯】RuleEngine.generateLegalMoves（设计文档 §5.3(a)）。
 * 关键性质：已剔除照面（Q7）、保留送将（Q2）、天然无 from==to（Q6/Q11）。
 */
class MoveGenerationTest {

    /**
     * 开局红方合法着法恰为 44 步。人工核算（每一类均按 §2.4 暗子虚拟类型规则）：
     * <pre>
     *   车 a0/i0：各 进1、进2（a3/i3 己兵挡）              = 4
     *   马 b0/h0：各 2（b0->a2,c2；d1 被 c0 蹩。h0 镜像）    = 4
     *   象 c0/g0：各 2（c0->a2,e2；g0->e2,i2；象眼皆空）     = 4
     *   士 d0/f0：各 1（唯 e1；c1/g1 出宫，暗士限宫）        = 2
     *   帅 e0  ：e1（d0/f0 己子；e 线有 e3/e6 挡，无照面）    = 1
     *   炮 b2/h2：各 12 = 进4(b3..b6) + 平6(a2,c2..g2)
     *             + 退1(b1) + 隔 b7 架吃 b9（h2 镜像吃 h9）  = 24
     *   兵 ×5  ：各进 1                                     = 5
     *   合计                                                = 44
     * </pre>
     * 黑方镜像同为 44。若实现与 44 不符，先对着上表找差异类别，再怀疑本表。
     */
    @Test
    @DisplayName("开局双方各 44 步（含炮隔架吃 b2xb9 / h2xh9 与暗子按虚拟类型全部走法）")
    void initialMoveCount() {
        BoardSnapshot b = BoardText.board(BoardText.INITIAL);
        List<Move> red = RuleEngine.generateLegalMoves(b, Color.RED);
        List<Move> black = RuleEngine.generateLegalMoves(b, Color.BLACK);
        assertEquals(44, red.size(), "红方开局步数");
        assertEquals(44, black.size(), "黑方开局步数");

        assertTrue(red.contains(Move.parse("c0e2")), "暗象走田（附录B判例1）");
        assertTrue(red.contains(Move.parse("b2b9")), "炮隔 b7 架吃 b9");
        assertTrue(red.contains(Move.parse("e0e1")), "帅进一（e 线有屏障，无照面）");
        assertTrue(red.contains(Move.parse("a3a4")), "暗兵进一");
        assertFalse(red.contains(Move.parse("d0c1")), "暗士不得出宫（附录B判例2）");
        assertFalse(red.contains(Move.parse("b0d1")), "蹩马腿（c0）");

        assertTrue(red.stream().noneMatch(m -> m.from().equals(m.to())), "天然无 from==to（Q6/Q11）");
        assertTrue(red.stream().allMatch(m -> RuleEngine.validate(b, Color.RED, m.from(), m.to()).legal()),
                "生成的每一步都必须通过 validate（生成与校验互证，§5.3）");
    }

    @Test
    @DisplayName("照面着法被剔除；沿将门线的走法保留（Q7）")
    void faceOffFiltered() {
        BoardSnapshot b = BoardText.board(MoveLegalityTest.FACEOFF);
        List<Move> red = RuleEngine.generateLegalMoves(b, Color.RED);
        assertFalse(red.contains(Move.parse("e4d4")), "车让开将门线 => 照面，须剔除");
        assertTrue(red.contains(Move.parse("e4e6")), "沿 e 线走保持屏障，合法");
    }

    @Test
    @DisplayName("送将/自被将军的着法保留（Q2）；照面仍剔除")
    void selfCheckKept() {
        BoardSnapshot b = BoardText.board(MoveLegalityTest.K1);
        List<Move> red = RuleEngine.generateLegalMoves(b, Color.RED);
        assertTrue(red.contains(Move.parse("e0e1")), "走入黑车横线（送将）合法保留");
        assertTrue(red.contains(Move.parse("e0f0")));
        assertFalse(red.contains(Move.parse("e0d0")), "与 d9 黑将照面，剔除");
    }
}
