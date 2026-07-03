package com.paiagent.engine.agent;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent单次执行的完整状态
 * 记录思考-行动-观察循环的全部历史
 */
@Data
public class AgentState {

    /**
     * 单次推理步骤（Thought/Action/Observation三元组）
     */
    @Data
    public static class Step {
        private final int iteration;
        private final String thought;       // LLM的思考过程
        private final String action;        // 决定调用的工具名称（null表示无行动）
        private final String actionInput;   // 工具的参数JSON（null表示无行动）
        private final String observation;   // 工具执行结果（null表示尚未执行）
        private final long timestamp;       // 步骤创建时间
    }

    private final String sessionId;         // 本次Agent执行会话ID
    private final String task;              // 用户原始任务/问题
    private final List<Step> steps;         // 执行步骤历史
    private int currentIteration;           // 当前迭代次数
    private boolean finished;               // 是否已完成
    private String finalAnswer;             // 最终答案（完成后填充）
    private String errorMessage;            // 错误信息（如发生异常）
    private final long createdAt;           // 状态创建时间
    private final int maxIterations;        // 最大允许迭代次数
    private String memoryContext;           // 记忆上下文（从知识库或执行记忆中检索）

    private String systemPrompt;

    public AgentState(String sessionId, String task, int maxIterations) {
        this.sessionId = sessionId;
        this.task = task;
        this.maxIterations = maxIterations;
        this.steps = new ArrayList<>();
        this.currentIteration = 0;
        this.finished = false;
        this.createdAt = System.currentTimeMillis();
    }

    /**
     * 添加新的思考步骤
     */
    public synchronized void addThought(int iteration, String thought) {
        // 如果已存在相同iteration的步骤，移除它（异常情况）
        steps.removeIf(step -> step.getIteration() == iteration);
        steps.add(new Step(iteration, thought, null, null, null, System.currentTimeMillis()));
        this.currentIteration = iteration;
    }

    /**
     * 为当前步骤添加行动（工具调用决定）
     */
    public synchronized void addAction(String action, String actionInput) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("Cannot add action before thought");
        }
        Step current = steps.get(steps.size() - 1);
        // Step是final字段的@Data，需要替换
        steps.set(steps.size() - 1, new Step(
                current.getIteration(),
                current.getThought(),
                action,
                actionInput,
                current.getObservation(),
                current.getTimestamp()
        ));
    }

    /**
     * 为当前步骤添加观察结果（工具执行结果）
     */
    public synchronized void addObservation(String observation) {
        if (steps.isEmpty()) {
            throw new IllegalStateException("Cannot add observation before thought");
        }
        Step current = steps.get(steps.size() - 1);
        steps.set(steps.size() - 1, new Step(
                current.getIteration(),
                current.getThought(),
                current.getAction(),
                current.getActionInput(),
                observation,
                current.getTimestamp()
        ));
    }

    /**
     * 标记Agent完成，设置最终答案
     */
    public synchronized void finish(String finalAnswer) {
        this.finished = true;
        this.finalAnswer = finalAnswer;
    }

    /**
     * 标记Agent失败，设置错误信息
     */
    public synchronized void fail(String errorMessage) {
        this.finished = true;
        this.errorMessage = errorMessage;
    }

    /**
     * 获取当前（最新）步骤
     */
    public synchronized Step getCurrentStep() {
        if (steps.isEmpty()) {
            return null;
        }
        return steps.get(steps.size() - 1);
    }

    /**
     * 检查是否达到最大迭代次数
     */
    public boolean hasReachedMaxIterations() {
        return currentIteration >= maxIterations;
    }

    /**
     * 设置记忆上下文
     */
    public void setMemoryContext(String memoryContext) {
        this.memoryContext = memoryContext;
    }

    /**
     * 获取记忆上下文
     */
    public String getMemoryContext() {
        return memoryContext;
    }

    /**
     * 获取完整的思考历史文本（用于构建LLM上下文）
     * 格式：
     * Thought: xxx
     * Action: xxx
     * Observation: xxx
     * ...
     */
    public synchronized String getHistoryAsText() {
        StringBuilder sb = new StringBuilder();
        for (Step step : steps) {
            // 只包含已完成的步骤（有observation的）
            if (step.getObservation() == null) {
                continue;
            }
            sb.append("Thought: ").append(step.getThought()).append("\n");
            if (step.getAction() != null) {
                sb.append("Action: ").append(step.getAction()).append("\n");
                if (step.getActionInput() != null) {
                    sb.append("Action Input: ").append(step.getActionInput()).append("\n");
                }
            }
            sb.append("Observation: ").append(step.getObservation()).append("\n\n");
        }
        return sb.toString().trim();
    }
}
