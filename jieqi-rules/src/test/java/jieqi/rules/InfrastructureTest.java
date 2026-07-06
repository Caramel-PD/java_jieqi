package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试基础设施自检（设计文档 §10.1 的"局面 FEN 式文本"载体）。
 * <b>本文件交付即绿</b>——它验证的是判例测试赖以表达局面的语言本身；
 * 若本文件红，先修基础设施，不要动规则测试。
 */
class InfrastructureTest {

    @Test
    @DisplayName("开局文本往返：parse -> format 恢复原文，行棋方=红先（§2.1）")
    void initialRoundTrip() {
        BoardText.ParsedPosition p = BoardText.parse(BoardText.INITIAL);
        assertEquals(Color.RED, p.sideToMove());
        assertEquals(BoardText.INITIAL, BoardText.format(p.board(), p.sideToMove()));
    }

    @Test
    @DisplayName("开局子力：每方 16 子、15 暗，双王明置 e0/e9（§2.2，initialBoard 32 对象口径）")
    void initialCensus() {
        BoardSnapshot b = BoardText.board(BoardText.INITIAL);
        assertEquals(16, b.countPieces(Color.RED));
        assertEquals(16, b.countPieces(Color.BLACK));
        assertEquals(15, b.countHidden(Color.RED));
        assertEquals(15, b.countHidden(Color.BLACK));
        assertEquals(new CellState.Revealed(Color.RED, PieceType.KING), b.cellAt(Coord.parse("e0")));
        assertEquals(new CellState.Revealed(Color.BLACK, PieceType.KING), b.cellAt(Coord.parse("e9")));
        assertInstanceOf(CellState.Hidden.class, b.cellAt(Coord.parse("a0")));
        assertTrue(b.cellAt(Coord.parse("e4")).isEmpty());
    }

    @Test
    @DisplayName("初始布局表 = 暗子虚拟类型唯一权威（§2.5 / Q8）")
    void virtualTypes() {
        assertEquals(PieceType.ROOK, InitialLayout.virtualTypeAt(Coord.parse("a0")));
        assertEquals(PieceType.KNIGHT, InitialLayout.virtualTypeAt(Coord.parse("h9")));
        assertEquals(PieceType.BISHOP, InitialLayout.virtualTypeAt(Coord.parse("c0")));
        assertEquals(PieceType.GUARD, InitialLayout.virtualTypeAt(Coord.parse("d9")));
        assertEquals(PieceType.KING, InitialLayout.virtualTypeAt(Coord.parse("e0")));
        assertEquals(PieceType.CANNON, InitialLayout.virtualTypeAt(Coord.parse("b2")));
        assertEquals(PieceType.PAWN, InitialLayout.virtualTypeAt(Coord.parse("e6")));
        assertNull(InitialLayout.virtualTypeAt(Coord.parse("c5")));
        // 将帅位不是"暗子占位格"：王开局即明（§2.2）
        assertFalse(InitialLayout.isDarkOriginSquare(Coord.parse("e0")));
        assertTrue(InitialLayout.isDarkOriginSquare(Coord.parse("a0")));
    }

    @Test
    @DisplayName("解析护栏：暗子摆在非初始占位格 => 拒绝（§2.5 暗子恒在原始格）")
    void hiddenPieceGuard() {
        // e4 不是任何初始占位格
        assertThrows(IllegalArgumentException.class,
                () -> BoardText.parse("4k4/9/9/9/9/4x4/9/9/9/4K4 r"));
        // e9 是将位——将帅永远不可能是暗子
        assertThrows(IllegalArgumentException.class,
                () -> BoardText.parse("4x4/9/9/9/9/9/9/9/9/4K4 r"));
        // 行宽不足 9 列
        assertThrows(IllegalArgumentException.class,
                () -> BoardText.parse("4k4/8/9/9/9/9/9/9/9/4K4 r"));
    }

    @Test
    @DisplayName("机械 apply：平移 / 吃子 / 暗子翻明（不做合法性判断，§5.2 职责边界）")
    void applyMechanics() {
        BoardSnapshot b = BoardText.board(BoardText.INITIAL);

        // 暗象 c0 -> e2，翻明为（服务器抽出的）相
        BoardSnapshot b2 = b.apply(Coord.parse("c0"), Coord.parse("e2"), PieceType.BISHOP);
        assertEquals(new CellState.Revealed(Color.RED, PieceType.BISHOP), b2.cellAt(Coord.parse("e2")));
        assertTrue(b2.cellAt(Coord.parse("c0")).isEmpty());
        assertEquals(14, b2.countHidden(Color.RED));
        assertEquals(16, b2.countPieces(Color.RED));
        assertEquals(15, b.countHidden(Color.RED), "快照不可变：原对象不受影响");

        // 明子吃子：炮 b2 隔 b5 架吃 b9（几何合法性由 RuleEngine 判，这里只验机械效果）
        BoardSnapshot c = BoardText.board("1r2k4/9/9/9/1p7/4P4/9/1C7/9/4K4 r");
        BoardSnapshot c2 = c.apply(Coord.parse("b2"), Coord.parse("b9"), null);
        assertEquals(new CellState.Revealed(Color.RED, PieceType.CANNON), c2.cellAt(Coord.parse("b9")));
        assertEquals(2, c2.countPieces(Color.BLACK), "黑方 车 被吃：3 -> 2");

        // 护栏：from==to / 起点空 / 暗子未给翻子类型
        assertThrows(IllegalArgumentException.class, () -> b.apply(Coord.parse("a0"), Coord.parse("a0"), null));
        assertThrows(IllegalArgumentException.class, () -> b.apply(Coord.parse("c5"), Coord.parse("c6"), null));
        assertThrows(IllegalArgumentException.class, () -> b.apply(Coord.parse("a0"), Coord.parse("a1"), null));
    }

    @Test
    @DisplayName("Zobrist：跨实例确定、对局面与行棋方敏感（§2.10 键空间约定）")
    void zobrist() {
        BoardSnapshot b1 = BoardText.board(BoardText.INITIAL);
        BoardSnapshot b2 = BoardText.board(BoardText.INITIAL);
        assertEquals(ZobristHash.of(b1, Color.RED), ZobristHash.of(b2, Color.RED), "同局面同哈希（确定性）");
        assertNotEquals(ZobristHash.of(b1, Color.RED), ZobristHash.of(b1, Color.BLACK), "行棋方入哈希");
        BoardSnapshot moved = b1.apply(Coord.parse("a3"), Coord.parse("a4"), PieceType.PAWN);
        assertNotEquals(ZobristHash.of(b1, Color.BLACK), ZobristHash.of(moved, Color.BLACK), "局面变则哈希变");
    }
}
