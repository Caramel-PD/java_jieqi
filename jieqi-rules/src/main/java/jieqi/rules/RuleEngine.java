package jieqi.rules;

import jieqi.common.Color;
import jieqi.common.Coord;
import jieqi.common.Move;
import jieqi.common.PieceType;

import java.util.ArrayList;
import java.util.List;

/**
 * 规则引擎（设计文档 §5.2 / §5.3）。纯函数、无状态、无随机、无 IO ——
 * 服务器 ServerBoard 与 AI PlayerView 共用同一套裁决（规则单点原则，§3.4-4）。
 *
 * <p>三条最易写错的裁定：
 * <ul>
 *   <li>送将/不应将<b>合法</b>（Q2）——本引擎不过滤"走后被将军"；</li>
 *   <li>造成将帅照面的着法<b>非法</b>（Q7）——本引擎必须过滤；</li>
 *   <li>暗子按其所在原始格的虚拟类型走一步（Q8），暗士限九宫、暗象天然不过河，
 *       明士明象按作业强化规则（§2.4）。</li>
 * </ul>
 */
public final class RuleEngine {

    private RuleEngine() {}

    /** 校验 mover 方走 from→to 的合法性（不含轮次与计时；次序契约见 {@link Legality}）。 */
    public static Legality validate(BoardSnapshot board, Color mover, Coord from, Coord to) {
        if (from.equals(to)) {
            return Legality.illegal(Legality.IllegalReason.FROM_EQUALS_TO);        // Q6/Q11
        }
        CellState src = board.cellAt(from);
        if (src.isEmpty()) {
            return Legality.illegal(Legality.IllegalReason.SOURCE_EMPTY);
        }
        if (src.color() != mover) {
            return Legality.illegal(Legality.IllegalReason.WRONG_COLOR);
        }
        CellState dst = board.cellAt(to);
        if (!dst.isEmpty() && dst.color() == mover) {
            return Legality.illegal(Legality.IllegalReason.DESTINATION_OWN_PIECE);
        }
        boolean revealed = src instanceof CellState.Revealed;
        PieceType type = revealed
                ? ((CellState.Revealed) src).type()
                : InitialLayout.virtualTypeAt(from);   // 暗子恒在原始格，查表恒非 null（§2.5）
        if (!geometryOk(board, mover, type, revealed, from, to)) {
            return Legality.illegal(Legality.IllegalReason.PIECE_RULE_VIOLATION);
        }
        if (createsKingFaceOff(board, from, to)) {
            return Legality.illegal(Legality.IllegalReason.KING_FACE_OFF);         // Q7
        }
        return Legality.ok();
    }

    /**
     * 生成 side 方全部合法着法（§5.3(a)）。已排除照面（Q7）；<b>不</b>排除走后被将军（Q2）；
     * 天然不含 from==to。生成的每一步都保证通过 {@link #validate}（生成与校验互证）。
     */
    public static List<Move> generateLegalMoves(BoardSnapshot board, Color side) {
        List<Move> moves = new ArrayList<>(48);
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                Coord from = new Coord(f, r);
                CellState src = board.cellAt(from);
                if (src.isEmpty() || src.color() != side) continue;
                boolean revealed = src instanceof CellState.Revealed;
                PieceType type = revealed
                        ? ((CellState.Revealed) src).type()
                        : InitialLayout.virtualTypeAt(from);
                for (Coord to : candidateSquares(type, from)) {
                    if (validate(board, side, from, to).legal()) {
                        moves.add(new Move(from, to));
                    }
                }
            }
        }
        return moves;
    }

    /** 若执行 from→to 后双方将帅同列且其间无子，返回 true（该着即非法，Q7）。§5.3(c)。 */
    public static boolean createsKingFaceOff(BoardSnapshot board, Coord from, Coord to) {
        Coord redKing = kingAfter(board, Color.RED, from, to);
        Coord blackKing = kingAfter(board, Color.BLACK, from, to);
        if (redKing == null || blackKing == null) return false;   // 有王已被吃：不存在照面
        if (redKing.file() != blackKing.file()) return false;
        int file = redKing.file();
        int lo = Math.min(redKing.rank(), blackKing.rank());
        int hi = Math.max(redKing.rank(), blackKing.rank());
        for (int r = lo + 1; r < hi; r++) {
            if (occupiedAfter(board, from, to, new Coord(file, r))) return false;
        }
        return true;
    }

    /** justMoved 方走完后，其任一棋子按当前身份下一步可吃到对方将帅（§2.10 将军步定义）。 */
    public static boolean givesCheck(BoardSnapshot board, Color justMoved) {
        Coord enemyKing = findKing(board, justMoved.opposite());
        if (enemyKing == null) return false;   // 王已被吃，终局态
        // 可证暗子（恒在原始格）无法将军：所有暗子原位按虚拟类型的一步可达范围
        // （含车炮直线）均不与对方将帅可能所在的九宫（d–f 列 × 0–2 / 7–9 行）相交。
        // 故只需扫描明子；扫全部亦正确，此处扫全部并依赖几何自然排除，保守起见。
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                Coord at = new Coord(f, r);
                CellState s = board.cellAt(at);
                if (s.isEmpty() || s.color() != justMoved) continue;
                boolean revealed = s instanceof CellState.Revealed;
                PieceType type = revealed
                        ? ((CellState.Revealed) s).type()
                        : InitialLayout.virtualTypeAt(at);
                if (geometryOk(board, justMoved, type, revealed, at, enemyKing)) return true;
            }
        }
        return false;
    }

    /** victim 方的将帅是否已不在棋盘上（终局判据：王被实际吃掉，Q2）。 */
    public static boolean isKingCaptured(BoardSnapshot board, Color victim) {
        return findKing(board, victim) == null;
    }

    // ------------------------------------------------------------------
    // 内部：几何判定（明=真实类型规则，暗=虚拟类型 + 原始象棋限制，§2.4 双列表）
    // ------------------------------------------------------------------

    /**
     * from 处 mover 方的 type 子（revealed 指明/暗期）走/吃 to 是否符合该子走法几何。
     * 不含通用前置检查与照面；to 可为空格（平移）或对方棋子（吃）。
     * 包级可见：RepetitionTracker 推导"捉"关系时复用（attacks 语义 = 可一步吃到）。
     */
    static boolean geometryOk(BoardSnapshot board, Color mover, PieceType type,
                              boolean revealed, Coord from, Coord to) {
        int df = to.file() - from.file();
        int dr = to.rank() - from.rank();
        switch (type) {
            case KING:
                // 九宫内直行一格（将帅无强化）
                return to.inPalace(mover) && Math.abs(df) + Math.abs(dr) == 1;
            case ROOK:
                return (df == 0 || dr == 0) && countBetween(board, from, to) == 0;
            case KNIGHT: {
                if (!((Math.abs(df) == 1 && Math.abs(dr) == 2) || (Math.abs(df) == 2 && Math.abs(dr) == 1))) {
                    return false;
                }
                Coord leg = Math.abs(df) == 2
                        ? new Coord(from.file() + df / 2, from.rank())
                        : new Coord(from.file(), from.rank() + dr / 2);
                return board.cellAt(leg).isEmpty();                     // 蹩马腿（明暗均有效）
            }
            case CANNON: {
                if (df != 0 && dr != 0) return false;
                int between = countBetween(board, from, to);
                return board.cellAt(to).isEmpty() ? between == 0        // 平移路径须空
                        : between == 1;                                 // 隔一子（炮架）吃
            }
            case PAWN: {
                int forward = Coord.forward(mover);
                if (df == 0 && dr == forward) return true;              // 永远可进一格
                // 过河后可横移一格；永不后退。过河判据取 from 格（§2.4）
                return from.crossedRiver(mover) && dr == 0 && Math.abs(df) == 1;
            }
            case GUARD:
                if (!(Math.abs(df) == 1 && Math.abs(dr) == 1)) return false;
                // 暗士限九宫；明士强化：可离宫过河（§2.4）
                return revealed || to.inPalace(mover);
            case BISHOP: {
                if (!(Math.abs(df) == 2 && Math.abs(dr) == 2)) return false;
                Coord eye = new Coord(from.file() + df / 2, from.rank() + dr / 2);
                if (!board.cellAt(eye).isEmpty()) return false;         // 塞象眼（明暗均有效）
                // 暗象不过河；明相强化：可过河（§2.4）
                return revealed || !to.crossedRiver(mover);
            }
        }
        return false;
    }

    /** 同线两格之间（不含端点）的棋子数；非同线返回 -1 语义由调用方保证不发生。 */
    private static int countBetween(BoardSnapshot board, Coord a, Coord b) {
        int df = Integer.signum(b.file() - a.file());
        int dr = Integer.signum(b.rank() - a.rank());
        int n = 0;
        int f = a.file() + df, r = a.rank() + dr;
        while (f != b.file() || r != b.rank()) {
            if (!board.cellAt(new Coord(f, r)).isEmpty()) n++;
            f += df;
            r += dr;
        }
        return n;
    }

    /** color 方王在"执行 from→to 之后"的所在格；王被本着吃掉或不在盘上则返回 null。 */
    private static Coord kingAfter(BoardSnapshot board, Color color, Coord from, Coord to) {
        CellState moving = board.cellAt(from);
        if (moving instanceof CellState.Revealed rev
                && rev.type() == PieceType.KING && rev.color() == color) {
            return to;   // 本着移动的就是该方王
        }
        Coord king = findKing(board, color);
        if (king == null || king.equals(to)) return null;   // 不在盘上 / 被本着吃掉
        return king;
    }

    /** 执行 from→to 之后，格 c 是否有子（from 腾空、to 落子、其余不变）。 */
    private static boolean occupiedAfter(BoardSnapshot board, Coord from, Coord to, Coord c) {
        if (c.equals(from)) return false;
        if (c.equals(to)) return true;
        return !board.cellAt(c).isEmpty();
    }

    /** color 方明王所在格；不在盘上返回 null（王永远是明子，§2.2）。 */
    static Coord findKing(BoardSnapshot board, Color color) {
        for (int r = 0; r < 10; r++) {
            for (int f = 0; f < 9; f++) {
                Coord c = new Coord(f, r);
                if (board.cellAt(c) instanceof CellState.Revealed rev
                        && rev.type() == PieceType.KING && rev.color() == color) {
                    return c;
                }
            }
        }
        return null;
    }

    /** type 子自 from 的候选终点（几何超集，交给 validate 精筛；车炮为全射线）。 */
    private static List<Coord> candidateSquares(PieceType type, Coord from) {
        List<Coord> out = new ArrayList<>(20);
        int f = from.file(), r = from.rank();
        switch (type) {
            case KING, PAWN -> addAll(out, f + 1, r, f - 1, r, f, r + 1, f, r - 1);
            case GUARD -> addAll(out, f + 1, r + 1, f + 1, r - 1, f - 1, r + 1, f - 1, r - 1);
            case BISHOP -> addAll(out, f + 2, r + 2, f + 2, r - 2, f - 2, r + 2, f - 2, r - 2);
            case KNIGHT -> addAll(out,
                    f + 1, r + 2, f + 1, r - 2, f - 1, r + 2, f - 1, r - 2,
                    f + 2, r + 1, f + 2, r - 1, f - 2, r + 1, f - 2, r - 1);
            case ROOK, CANNON -> {
                for (int i = 0; i < 9; i++) if (i != f) out.add(new Coord(i, r));
                for (int i = 0; i < 10; i++) if (i != r) out.add(new Coord(f, i));
            }
        }
        return out;
    }

    private static void addAll(List<Coord> out, int... fr) {
        for (int i = 0; i < fr.length; i += 2) {
            int f = fr[i], r = fr[i + 1];
            if (f >= 0 && f <= 8 && r >= 0 && r <= 9) out.add(new Coord(f, r));
        }
    }
}
