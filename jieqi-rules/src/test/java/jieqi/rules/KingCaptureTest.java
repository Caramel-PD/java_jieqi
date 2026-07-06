package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Move;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【判例测试·红灯】终局判据 = 王被实际吃掉（Q2；附录 B 判例 15）。
 * 送将合法的直接推论：吃将的着法是普通合法着法；吃掉后 isKingCaptured 为真，
 * 服务器据此发 gameOver(reason=checkmate)（§2.8）。
 */
class KingCaptureTest {

    /** 红车 d0 与黑将 d9 同线且其间无子（黑正被将军但无须应将）。红帅 f0 不与黑将同列。 */
    static final String BOARD = "3k5/9/9/9/9/9/9/9/9/3R1K3 r";

    @Test
    @DisplayName("吃将着法 d0d9 是普通合法着法，且出现在走法生成中")
    void captureKingIsLegal() {
        BoardSnapshot b = BoardText.board(BOARD);
        assertTrue(RuleEngine.validate(b, Color.RED, Coord.parse("d0"), Coord.parse("d9")).legal());
        assertTrue(RuleEngine.generateLegalMoves(b, Color.RED).contains(Move.parse("d0d9")));
    }

    @Test
    @DisplayName("吃将后 isKingCaptured(BLACK)=true => 终局 checkmate")
    void kingCapturedAfterApply() {
        BoardSnapshot b = BoardText.board(BOARD);
        BoardSnapshot after = b.apply(Coord.parse("d0"), Coord.parse("d9"), null);
        assertTrue(RuleEngine.isKingCaptured(after, Color.BLACK));
        assertFalse(RuleEngine.isKingCaptured(after, Color.RED));
        assertFalse(RuleEngine.isKingCaptured(b, Color.BLACK), "吃前双王健在");
    }
}
