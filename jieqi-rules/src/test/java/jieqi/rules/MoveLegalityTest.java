package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Move;
import jieqi.rules.Legality.IllegalReason;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static jieqi.rules.Legality.IllegalReason.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 【判例测试·红灯】RuleEngine.validate 合法性裁定（设计文档 §2.4–§2.7、附录 B 判例 1–14）。
 * 用例名中的 Bnn 对应设计文档附录 B 编号；Qn 为教师问题回答编号。
 * 交付时全红（validate 为 TODO），实现 P0-1/P0-3 后应全绿。
 */
class MoveLegalityTest {

    // ---- 局面库（FEN 式，语法见 BoardText）。所有局面均已人工核对几何。----

    /** 开局。 */
    static final String INITIAL = BoardText.INITIAL;
    /** 开局 + 红马 d1：塞死 c0 暗象 -> e2 的象眼。 */
    static final String INIT_EYE_BLOCKED =
            "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/3N5/XXXXKXXXX r";
    /** 开局 + 红马 b1：蹩死 b0 暗马 -> c2 的马腿。 */
    static final String INIT_LEG_BLOCKED =
            "xxxxkxxxx/9/1x5x1/x1x1x1x1x/9/9/X1X1X1X1X/1X5X1/1N7/XXXXKXXXX r";
    /** 明相 c4 欲过河 e6，象眼 d5 空；红兵 e4 挡将门线。 */
    static final String ELE_CROSS = "4k4/9/9/9/9/2B1P4/9/9/9/4K4 r";
    /** 同上但黑卒 d5 塞象眼。 */
    static final String ELE_EYE = "4k4/9/9/9/3p5/2B1P4/9/9/9/4K4 r";
    /** 明仕 c4 欲过河 b5（作业强化规则）。 */
    static final String GUARD_ENH = "4k4/9/9/9/9/2G1P4/9/9/9/4K4 r";
    /** 炮 b2、单架 b5（黑卒）、目标 b9（黑车）。 */
    static final String CANNON1 = "1r2k4/9/9/9/1p7/4P4/9/1C7/9/4K4 r";
    /** 双架：b5 + b7。 */
    static final String CANNON2 = "1r2k4/9/1p7/9/1p7/4P4/9/1C7/9/4K4 r";
    /** 零架：b 线全空。 */
    static final String CANNON0 = "1r2k4/9/9/9/9/4P4/9/1C7/9/4K4 r";
    /** 未过河明兵 a3。 */
    static final String PAWN_PRE = "4k4/9/9/9/9/4P4/P8/9/9/4K4 r";
    /** 已过河明兵 a5。 */
    static final String PAWN_POST = "4k4/9/9/9/P8/4P4/9/9/9/4K4 r";
    /** 红车 e4 是双王之间唯一屏障。 */
    static final String FACEOFF = "4k4/9/9/9/9/4R4/9/9/9/4K4 r";
    /** 黑车 a1 控一路横线；黑将 d9（d 列黑将、e 列空 -> 帅平 d0 即照面）。 */
    static final String K1 = "3k5/9/9/9/9/9/9/9/r8/4K2N1 r";
    /** 黑车 a0 正将军红帅（Q2：可不应将）。 */
    static final String K2 = "3k5/9/9/9/9/9/9/9/9/r3K2N1 r";
    /** 红帅在宫顶 e2。 */
    static final String KING_EDGE = "3k5/9/9/9/9/9/9/4K4/9/9 r";

    record LegalityCase(String name, String board, Color mover, String move,
                        boolean legal, IllegalReason reason) {
        @Override
        public String toString() { return name; }
    }

    static LegalityCase ok(String name, String board, String move) {
        return new LegalityCase(name, board, Color.RED, move, true, null);
    }

    static LegalityCase bad(String name, String board, String move, IllegalReason reason) {
        return new LegalityCase(name, board, Color.RED, move, false, reason);
    }

    static Stream<LegalityCase> cases() {
        return Stream.of(
                // ---- 通用前置检查（次序契约见 Legality javadoc）----
                bad("B12_原地翻子from==to_a0a0(Q6/Q11)", INITIAL, "a0a0", FROM_EQUALS_TO),
                bad("起点无子_c5c6", INITIAL, "c5c6", SOURCE_EMPTY),
                bad("走对方棋子_红走b7b6", INITIAL, "b7b6", WRONG_COLOR),
                bad("终点己方棋子_a0a3", INITIAL, "a0a3", DESTINATION_OWN_PIECE),

                // ---- 暗子按虚拟类型（Q8），暗士限宫 / 暗象不过河天然成立 ----
                ok("B01_暗象c0走e2_象眼d1空(Q8)", INITIAL, "c0e2"),
                bad("B07a_暗象c0走e2_象眼d1被塞", INIT_EYE_BLOCKED, "c0e2", PIECE_RULE_VIOLATION),
                bad("B02_暗士d0出宫c1", INITIAL, "d0c1", PIECE_RULE_VIOLATION),
                ok("暗士d0走e1_宫内斜一格", INITIAL, "d0e1"),
                bad("B04_暗士f0直行f1", INITIAL, "f0f1", PIECE_RULE_VIOLATION),
                ok("暗马b0走c2_马腿b1空", INITIAL, "b0c2"),
                bad("B06a_暗马b0走c2_马腿b1被蹩", INIT_LEG_BLOCKED, "b0c2", PIECE_RULE_VIOLATION),
                bad("B06b_暗马b0走d1_马腿c0被蹩", INITIAL, "b0d1", PIECE_RULE_VIOLATION),
                ok("暗车a0进两格a2", INITIAL, "a0a2"),
                bad("暗车a0穿己子至a4", INITIAL, "a0a4", PIECE_RULE_VIOLATION),
                ok("暗兵a3进一格a4", INITIAL, "a3a4"),
                bad("暗兵a3未过河横移b3", INITIAL, "a3b3", PIECE_RULE_VIOLATION),

                // ---- 士象强化（作业原文，§2.4）：明士明相可过河，塞象眼始终有效 ----
                ok("B03_明相c4过河e6_象眼d5空", ELE_CROSS, "c4e6"),
                bad("B07b_明相c4过河e6_象眼d5被塞", ELE_EYE, "c4e6", PIECE_RULE_VIOLATION),
                ok("B05_明仕c4离宫过河b5", GUARD_ENH, "c4b5"),

                // ---- 炮（隔一子吃，平移路径须空）----
                ok("B08_炮b2隔单架b5吃b9", CANNON1, "b2b9"),
                bad("B09a_炮b2隔双架吃b9", CANNON2, "b2b9", PIECE_RULE_VIOLATION),
                bad("B09b_炮b2零架吃b9", CANNON0, "b2b9", PIECE_RULE_VIOLATION),
                bad("B09c_炮b2零架贴吃b5", CANNON1, "b2b5", PIECE_RULE_VIOLATION),
                bad("炮b2平移穿架至b6", CANNON1, "b2b6", PIECE_RULE_VIOLATION),
                ok("炮b2平移b4_路径空", CANNON1, "b2b4"),
                ok("炮b2平移b8_全线空", CANNON0, "b2b8"),

                // ---- 兵（过河前只进；过河后可平移；永不后退）----
                bad("B10a_未过河兵a3横移b3", PAWN_PRE, "a3b3", PIECE_RULE_VIOLATION),
                bad("B10b_兵a3后退a2", PAWN_PRE, "a3a2", PIECE_RULE_VIOLATION),
                ok("未过河兵a3前进a4", PAWN_PRE, "a3a4"),
                ok("B11_过河兵a5横移b5", PAWN_POST, "a5b5"),
                bad("过河兵a5后退a4", PAWN_POST, "a5a4", PIECE_RULE_VIOLATION),
                ok("过河兵a5前进a6", PAWN_POST, "a5a6"),

                // ---- 照面（Q7 非法）与送将/不应将（Q2 合法）----
                bad("B13a_车e4让开致照面_e4d4(Q7)", FACEOFF, "e4d4", KING_FACE_OFF),
                ok("车e4沿将门线走e6_屏障仍在", FACEOFF, "e4e6"),
                ok("B14a_帅e0走e1入黑车横线_送将合法(Q2)", K1, "e0e1"),
                bad("帅e0斜走d1", K1, "e0d1", PIECE_RULE_VIOLATION),
                bad("B13b_帅e0平d0与d9黑将照面(Q7)", K1, "e0d0", KING_FACE_OFF),
                ok("帅e0平f0", K1, "e0f0"),
                ok("B14b_被将军中走h0g2不应将_合法(Q2)", K2, "h0g2"),
                bad("帅e2出宫e3", KING_EDGE, "e2e3", PIECE_RULE_VIOLATION),
                ok("帅e2宫内平f2", KING_EDGE, "e2f2")
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void verdict(LegalityCase c) {
        BoardSnapshot board = BoardText.board(c.board());
        Move m = Move.parse(c.move());
        Legality got = RuleEngine.validate(board, c.mover(), m.from(), m.to());
        if (c.legal()) {
            assertTrue(got.legal(), () -> c.name() + "：应合法，实际 " + got.reason());
        } else {
            assertFalse(got.legal(), () -> c.name() + "：应非法(" + c.reason() + ")");
            assertEquals(c.reason(), got.reason(), () -> c.name() + "：非法原因不符（检查次序契约见 Legality）");
        }
    }
}
