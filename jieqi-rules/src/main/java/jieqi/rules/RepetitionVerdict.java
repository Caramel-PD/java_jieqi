package jieqi.rules;

/** 重复/无吃子裁定结果（设计文档 §2.9/§2.10）。由 TurnEngine 映射到 gameOver reason。 */
public enum RepetitionVerdict {
    NONE,
    /** 长将判负 / 非兵卒长捉判负 / 兵卒长将判负（Q4/Q5）：实施方（本步 mover）负。 */
    REPETITION_LOSS,
    /** 兵卒长捉任何子判和（Q5）。 */
    REPETITION_DRAW,
    /** 80 半步（=40 回合）无吃子判和；翻子不算吃子（Q3）。 */
    DRAW_NO_CAPTURE
}
