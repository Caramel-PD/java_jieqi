package jieqi.common;

/**
 * 规则层着法：仅 (from, to)。
 *
 * <p>与设计文档 §5.2 的领域 Move 类（source/destination/type/turnStartTime/isFlip/captured）的关系：
 * 那些字段属于服务器/协议层（jieqi-server 的 TurnEngine 负责纠正 isFlip、HiddenPool 负责抽取 type）。
 * 规则层只关心几何与合法性；是否翻子可由棋盘 {@code board.cellAt(from) instanceof Hidden} 直接推出，
 * 不在此冗余存储（不允许原地翻子 =&gt; from 恒不等于 to，见 Q6/Q11）。
 */
public record Move(Coord from, Coord to) {

    public Move {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from/to required");
        }
    }

    /** 解析 "a1a9" 或 "a1-a9"。 */
    public static Move parse(String s) {
        String t = s.replace("-", "");
        if (t.length() != 4) {
            throw new IllegalArgumentException("bad move: " + s);
        }
        return new Move(Coord.parse(t.substring(0, 2)), Coord.parse(t.substring(2, 4)));
    }

    @Override
    public String toString() {
        return from.toString() + to.toString();
    }
}
