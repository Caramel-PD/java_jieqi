package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.PieceType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 长将 / 长捉 / 80 半步无吃子状态机（设计文档 §2.10 伪代码的对象化）。
 *
 * <p><b>分层设计</b>：核心是纯状态机 {@link #onFacts(MoveFacts)} —— 不查棋盘、不算哈希，
 * 只消费上游给定的"事实"，可被事实脚本彻底单测。{@link #onMoveApplied} 是设计文档 §5.2
 * 签名的便捷包装：内部用 {@link RuleEngine#givesCheck}、{@link ZobristHash#of} 推导事实后委托核心。
 *
 * <p><b>裁定要点</b>（Q3/Q4/Q5，详见 §2.10）：
 * <ul>
 *   <li>每方各自独立维护 checkStreak / chaseStreak 及对应哈希序列；</li>
 *   <li><b>必须连续</b>：中断（一步非将 / 换捉目标 / 无捉）即清零（换目标时 chaseStreak 重置为 1）；</li>
 *   <li>判罚点：streak &gt; repetitionLimit(6) 且当前连续序列内同一局面哈希出现 ≥ minRepeats(3)；</li>
 *   <li>归属：长将 → 实施方负（含兵卒长将）；长捉 → 非兵卒实施方负，<b>兵卒长捉任何子 → 和</b>；</li>
 *   <li><b>任何吃子</b>：双方全部 streak 与 80 半步计数一并清零；<b>翻子不算吃子</b>（Q3）。
 *       计数约定：吃子的那个半步本身把计数清为 0，其后第一个非吃子半步计为 1；</li>
 *   <li>80 半步（双方合计）无吃子 → DRAW_NO_CAPTURE；先判重复罚则、后判 80 半步。</li>
 * </ul>
 */
public final class RepetitionTracker {

    /**
     * 一个半步的裁定事实（由 TurnEngine / 测试脚本提供）。
     *
     * @param mover             本步行棋方
     * @param from              起点（用于对方跟踪被捉目标的移动）
     * @param to                终点
     * @param positionHashAfter 走后局面哈希（约定 = ZobristHash.of(after, mover.opposite())）
     * @param capture           本步是否吃子（吃明子或暗子皆算；<b>翻子不是吃子</b>）
     * @param flip              本步是否翻子（对状态机的效果：不清零任何计数）
     * @param givesCheck        走后是否将军对方（§2.10 将军步定义）
     * @param chasedTargetAfter 走后本步所动之子"捉"的目标格（§2.10 简化判据）；无捉 = null。
     *                          <b>目标同一性跟踪</b>：双方每个半步都必须流经本状态机——当对方某步的
     *                          from 等于我方当前记录的被捉目标格时，目标格更新为该步的 to。
     * @param moverTypeAfter    所动之子走后的类型（暗子=翻出的类型）——兵卒特例判定用（Q5）
     */
    public record MoveFacts(
            Color mover,
            Coord from,
            Coord to,
            long positionHashAfter,
            boolean capture,
            boolean flip,
            boolean givesCheck,
            Coord chasedTargetAfter,
            PieceType moverTypeAfter) {}

    private static final class SideState {
        int checkStreak;
        final List<Long> checkHashes = new ArrayList<>();
        int chaseStreak;
        final List<Long> chaseHashes = new ArrayList<>();
        Coord lastChaseTarget;

        void reset() {
            checkStreak = 0;
            checkHashes.clear();
            chaseStreak = 0;
            chaseHashes.clear();
            lastChaseTarget = null;
        }
    }

    private final int repetitionLimit;
    private final int minRepeats;
    private final int noCaptureLimitHalfMoves;
    private final EnumMap<Color, SideState> sides = new EnumMap<>(Color.class);
    private int noCaptureHalfMoves;

    /** 缺省阈值：连续 &gt;6 次 + 序列内同局面 ≥3 次；80 半步（§2.10 / §11.3 配置表）。 */
    public RepetitionTracker() {
        this(6, 3, 80);
    }

    public RepetitionTracker(int repetitionLimit, int minRepeats, int noCaptureLimitHalfMoves) {
        this.repetitionLimit = repetitionLimit;
        this.minRepeats = minRepeats;
        this.noCaptureLimitHalfMoves = noCaptureLimitHalfMoves;
        sides.put(Color.RED, new SideState());
        sides.put(Color.BLACK, new SideState());
    }

    /** 核心状态机：消费一个半步的事实，返回裁定。 */
    public RepetitionVerdict onFacts(MoveFacts f) {
        SideState me = sides.get(f.mover());
        SideState opp = sides.get(f.mover().opposite());

        // 1) 吃子：一切计数清零（Q4/Q5 备注：吃子发生时所有计数清零）；翻子不算吃子（Q3）
        if (f.capture()) {
            sides.values().forEach(SideState::reset);
            noCaptureHalfMoves = 0;
        } else {
            noCaptureHalfMoves++;
        }

        // 2) 目标同一性跟踪：被对方捉着的子（=对方记录的目标格）本步移动 => 目标格随之更新
        if (opp.lastChaseTarget != null && opp.lastChaseTarget.equals(f.from())) {
            opp.lastChaseTarget = f.to();
        }

        // 3) 将军连续性（§2.10 状态机）
        if (f.givesCheck()) {
            me.checkStreak++;
            me.checkHashes.add(f.positionHashAfter());
        } else {
            me.checkStreak = 0;
            me.checkHashes.clear();
        }

        // 4) 捉子连续性：同一目标才累计；换目标重置为 1；无捉清零
        Coord t = f.chasedTargetAfter();
        if (t != null && t.equals(me.lastChaseTarget)) {
            me.chaseStreak++;
            me.chaseHashes.add(f.positionHashAfter());
        } else if (t != null) {
            me.chaseStreak = 1;
            me.chaseHashes.clear();
            me.chaseHashes.add(f.positionHashAfter());
        } else {
            me.chaseStreak = 0;
            me.chaseHashes.clear();
        }
        me.lastChaseTarget = t;

        // 5) 判罚（罚则优先于 80 半步）
        if (me.checkStreak > repetitionLimit && hasRepeat(me.checkHashes)) {
            return RepetitionVerdict.REPETITION_LOSS;   // 长将判负，含兵卒长将（Q5）
        }
        if (me.chaseStreak > repetitionLimit && hasRepeat(me.chaseHashes)) {
            return f.moverTypeAfter() == PieceType.PAWN
                    ? RepetitionVerdict.REPETITION_DRAW  // 兵卒长捉任何子 => 和（Q5）
                    : RepetitionVerdict.REPETITION_LOSS;
        }
        if (noCaptureHalfMoves >= noCaptureLimitHalfMoves) {
            return RepetitionVerdict.DRAW_NO_CAPTURE;    // 80 半步无吃子（Q3）
        }
        return RepetitionVerdict.NONE;
    }

    /**
     * 设计文档 §5.2 签名的便捷包装：由 before/after 推导事实后委托 {@link #onFacts}。
     * 捉目标推导按 §2.10 简化判据：本步所动之子在 after 局面上攻击的对方非将帅棋子；
     * 多目标取舍策略（记录在案并保持稳定）：优先沿用当前跟踪中的目标（连续性优先），
     * 否则取棋盘扫描序（rank 升序、file 升序）第一个。
     */
    public RepetitionVerdict onMoveApplied(
            BoardSnapshot before, BoardSnapshot after, Color mover, Coord from, Coord to, boolean capture) {
        boolean flip = before.cellAt(from) instanceof CellState.Hidden;
        boolean check = RuleEngine.givesCheck(after, mover);
        long hash = ZobristHash.of(after, mover.opposite());
        PieceType typeAfter = ((CellState.Revealed) after.cellAt(to)).type();
        Coord chase = deriveChaseTarget(after, mover, to, typeAfter);
        return onFacts(new MoveFacts(mover, from, to, hash, capture, flip, check, chase, typeAfter));
    }

    /** 自上次吃子以来的半步数（UI 展示 / 服务器判和用）。 */
    public int noCaptureHalfMoves() {
        return noCaptureHalfMoves;
    }

    public int repetitionLimit() {
        return repetitionLimit;
    }

    public int minRepeats() {
        return minRepeats;
    }

    public int noCaptureLimitHalfMoves() {
        return noCaptureLimitHalfMoves;
    }

    /** 当前 side 的连续将军次数（AI 的 RepRisk 项使用，§8.6）。 */
    public int checkStreak(Color side) {
        return sides.get(side).checkStreak;
    }

    /** 当前 side 的连续捉子次数（AI 的 RepRisk 项使用，§8.6）。 */
    public int chaseStreak(Color side) {
        return sides.get(side).chaseStreak;
    }

    // ------------------------------------------------------------------

    private boolean hasRepeat(List<Long> hashes) {
        Map<Long, Integer> counts = new HashMap<>();
        for (long h : hashes) {
            int c = counts.merge(h, 1, Integer::sum);
            if (c >= minRepeats) return true;
        }
        return false;
    }

    private Coord deriveChaseTarget(BoardSnapshot after, Color mover, Coord at, PieceType typeAfter) {
        Coord preferred = sides.get(mover).lastChaseTarget;
        Coord first = null;
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                Coord c = new Coord(f, r);
                CellState s = after.cellAt(c);
                if (s.isEmpty() || s.color() == mover) continue;
                if (s instanceof CellState.Revealed rev && rev.type() == PieceType.KING) continue;  // 非将帅
                if (!RuleEngine.geometryOk(after, mover, typeAfter, true, at, c)) continue;
                if (c.equals(preferred)) return c;     // 连续性优先
                if (first == null) first = c;
            }
        }
        return first;
    }
}
