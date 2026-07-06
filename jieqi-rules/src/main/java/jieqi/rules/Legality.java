package jieqi.rules;

/**
 * 合法性裁决结果。非法时统一映射协议错误码 2001（设计文档 §2.7 / §4.6；
 * 轮次错误 2002 与超时 2003 不在规则层，由 jieqi-server 的 TurnEngine / GameClock 裁定）。
 *
 * <p><b>检查次序契约</b>（测试按此断言 reason，实现必须遵守，§2.7 清单 1–7 条的规则层子集）：
 * <ol>
 *   <li>FROM_EQUALS_TO —— from==to（不允许原地翻子，Q6/Q11）</li>
 *   <li>SOURCE_EMPTY —— 起点无子</li>
 *   <li>WRONG_COLOR —— 起点棋子不属于行棋方</li>
 *   <li>DESTINATION_OWN_PIECE —— 终点为己方棋子</li>
 *   <li>PIECE_RULE_VIOLATION —— 违反该子（明=真实类型 / 暗=虚拟类型）的走法几何、路径、
 *       蹩腿象眼炮架、暗士限宫暗象不过河、兵不后退等（§2.4）</li>
 *   <li>KING_FACE_OFF —— 走后将帅照面（Q7）。注意：走后被将军 / 送将<b>不在</b>检查之列（Q2 合法）</li>
 * </ol>
 */
public record Legality(boolean legal, IllegalReason reason) {

    public enum IllegalReason {
        FROM_EQUALS_TO,
        SOURCE_EMPTY,
        WRONG_COLOR,
        DESTINATION_OWN_PIECE,
        PIECE_RULE_VIOLATION,
        KING_FACE_OFF
    }

    private static final Legality LEGAL = new Legality(true, null);

    public static Legality ok() {
        return LEGAL;
    }

    public static Legality illegal(IllegalReason reason) {
        if (reason == null) throw new IllegalArgumentException("reason required");
        return new Legality(false, reason);
    }
}
