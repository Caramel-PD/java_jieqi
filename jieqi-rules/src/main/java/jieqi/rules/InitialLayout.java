package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;

/**
 * 标准象棋初始布局表（设计文档 §2.2）。【已实现——测试基础设施】
 *
 * <p>用途（§2.5）：暗子恒在原始格 =&gt; 暗子的虚拟类型 = 本表在该坐标的类型。
 * 这是全工程"虚拟类型"的唯一权威来源；也用于 gameStart.initialBoard 的虚拟类型填充（§4.4 / Q37）。
 */
public final class InitialLayout {

    private InitialLayout() {}

    /** 底线自 a 至 i：车 马 象 士 将 士 象 马 车。 */
    private static final PieceType[] BACK_RANK = {
            PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP, PieceType.GUARD, PieceType.KING,
            PieceType.GUARD, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK
    };

    /**
     * 该坐标在标准初始布局中的棋子类型；非初始占位格返回 null。
     * 红：rank0 底线、rank2 的 b/h 炮、rank3 的 a/c/e/g/i 兵；黑镜像于 rank9/7/6。
     */
    public static PieceType virtualTypeAt(Coord c) {
        int f = c.file(), r = c.rank();
        if (r == 0 || r == 9) return BACK_RANK[f];
        if ((r == 2 || r == 7) && (f == 1 || f == 7)) return PieceType.CANNON;
        if ((r == 3 || r == 6) && f % 2 == 0) return PieceType.PAWN;
        return null;
    }

    /** 该坐标是否为 side 方的初始占位格。 */
    public static boolean isOriginSquare(Coord c, Color side) {
        if (virtualTypeAt(c) == null) return false;
        return side == Color.RED ? c.rank() <= 3 : c.rank() >= 6;
    }

    /**
     * 该坐标是否可能存在暗子：初始占位格且非将帅位（将帅开局即明、且永远不可能是暗子，§2.2）。
     * BoardText 解析器据此拒绝摆放在非法位置的暗子——测试局面的合法性护栏。
     */
    public static boolean isDarkOriginSquare(Coord c) {
        PieceType t = virtualTypeAt(c);
        return t != null && t != PieceType.KING;
    }
}
