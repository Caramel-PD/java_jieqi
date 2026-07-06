package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.PieceType;

/**
 * 一格的状态（设计文档 §5.2）：空 / 暗子(仅颜色可见) / 明子(颜色+类型)。
 *
 * <p>暗子不携带类型：由 Q6/Q11（不允许原地翻子）可推出暗子恒在其原始格，
 * 其"虚拟类型"= {@link InitialLayout#virtualTypeAt(jieqi.common.Coord)}，查表即得，无需存储（§2.5）。
 * 真实类型在服务器侧由 HiddenPool 于揭示时刻抽取（Q9），不属于规则层状态。
 */
public sealed interface CellState permits CellState.Empty, CellState.Hidden, CellState.Revealed {

    record Empty() implements CellState {
        public static final Empty INSTANCE = new Empty();
    }

    /** 暗子：仅颜色公开。 */
    record Hidden(Color color) implements CellState {}

    /** 明子：颜色 + 真实类型公开。 */
    record Revealed(Color color, PieceType type) implements CellState {}

    default boolean isEmpty() {
        return this instanceof Empty;
    }

    /** 非空格的所属颜色；空格抛异常。 */
    default Color color() {
        if (this instanceof Hidden h) return h.color();
        if (this instanceof Revealed r) return r.color();
        throw new IllegalStateException("empty cell has no color");
    }
}
