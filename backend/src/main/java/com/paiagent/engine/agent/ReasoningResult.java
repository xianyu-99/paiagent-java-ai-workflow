package com.paiagent.engine.agent;

import lombok.Data;

/**
 * 推理结果
 */
@Data
public class ReasoningResult {

    public enum DecisionType {
        THOUGHT,        // 纯思考，无行动
        ACTION,         // 需要调用工具
        FINAL_ANSWER,   // 给出最终答案
        ERROR           // 发生错误（如解析失败）
    }

    private final DecisionType type;
    private final String thought;       // 思考内容（所有类型都有）
    private final String action;        // 工具名称（ACTION类型时有值）
    private final String actionInput;   // 工具参数JSON（ACTION类型时有值）
    private final String finalAnswer;   // 最终答案（FINAL_ANSWER类型时有值）
    private final String errorMessage;  // 错误信息（ERROR类型时有值）

    public static ReasoningResult action(String thought, String action, String actionInput) {
        return new ReasoningResult(DecisionType.ACTION, thought, action, actionInput, null, null);
    }

    public static ReasoningResult finalAnswer(String thought, String finalAnswer) {
        return new ReasoningResult(DecisionType.FINAL_ANSWER, thought, null, null, finalAnswer, null);
    }

    public static ReasoningResult error(String errorMessage) {
        return new ReasoningResult(DecisionType.ERROR, null, null, null, null, errorMessage);
    }
}
