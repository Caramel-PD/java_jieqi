package jieqi.common;

/** 公共接口错误码（设计文档 §4.6 全表）。 */
public enum ErrorCode {
    LOGIN_FAILED(1001),
    DUPLICATE_LOGIN(1002),
    ILLEGAL_MOVE(2001),      // 规则不符：含 from==to、照面（§2.7 清单 3–7 条）
    NOT_YOUR_TURN(2002),
    MOVE_OVERTIME(2003),
    ROOM_NOT_FOUND(3001),
    MATCH_FAILED(3002),
    BAD_JSON(4001);

    public final int code;

    ErrorCode(int code) { this.code = code; }
}
