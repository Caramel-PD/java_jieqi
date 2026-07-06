package jieqi.common;

/**
 * gameOver.reason 取值（设计文档 §4.3 表 + §14 问题 3 的扩展枚举）。
 * 公共接口原生值：checkmate / timeout / resign；其余为本组扩展值，
 * 跨组联调时对端未知值须能容错（宽进严出，§4.9）。
 */
public enum GameOverReason {
    CHECKMATE("checkmate"),          // 含"直接吃掉将帅获胜"的统一映射（Q2）
    TIMEOUT("timeout"),
    RESIGN("resign"),
    STALEMATE("stalemate"),          // 困毙：轮走方无任何合法着法（极罕见，送将合法所致）
    DISCONNECT("disconnect"),
    REPETITION_LOSS("repetition_loss"),   // 长将 / 长捉判负（含兵卒长将，Q4/Q5）
    REPETITION_DRAW("repetition_draw"),   // 兵卒长捉任何子判和（Q5）
    DRAW_NO_CAPTURE("draw_no_capture"),   // 40 回合 = 80 半步无吃子（翻子不算吃子，Q3）
    DRAW_AGREED("draw_agreed");           // 协议和棋（Q20，扩展消息）

    private final String json;

    GameOverReason(String json) { this.json = json; }

    public String json() { return json; }

    public static GameOverReason fromJson(String s) {
        for (GameOverReason r : values()) {
            if (r.json.equalsIgnoreCase(s)) return r;
        }
        throw new IllegalArgumentException("unknown reason: " + s);
    }
}
