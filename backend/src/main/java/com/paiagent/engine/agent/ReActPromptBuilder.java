package com.paiagent.engine.agent;

import com.alibaba.fastjson2.JSON;
import com.paiagent.engine.agent.tool.Tool;

import java.util.List;

/**
 * ReAct格式的提示词构建器
 */
public class ReActPromptBuilder {

    /**
     * 构建系统提示词
     * 包含：角色定义、工具列表及描述、参数Schema、ReAct格式说明、注意事项
     */
    public static String buildSystemPrompt(List<Tool> tools, String customInstructions) {
        return buildSystemPrompt(tools, customInstructions, null);
    }

    /**
     * 构建系统提示词（支持记忆上下文）
     */
    public static String buildSystemPrompt(List<Tool> tools, String customInstructions, String memoryContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a helpful assistant that can use tools to solve problems.\n\n");

        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append(customInstructions).append("\n\n");
        }

        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append("Relevant context from memory:\n").append(memoryContext).append("\n\n");
        }

        sb.append("Available tools:\n");
        sb.append(formatTools(tools));
        sb.append("\n\n");

        sb.append("You must respond in the following format:\n");
        sb.append("Thought: your reasoning about what to do next\n");
        sb.append("Action: tool_name\n");
        sb.append("Action Input: {\"param1\": \"value1\", ...}\n\n");

        sb.append("OR if you have the final answer:\n");
        sb.append("Thought: your reasoning\n");
        sb.append("Final Answer: your final answer to the user\n\n");

        sb.append("Important notes:\n");
        sb.append("- Always start with a Thought.\n");
        sb.append("- Action Input must be valid JSON matching the tool's parameter schema.\n");
        sb.append("- Do not make up tools that are not listed above.\n");
        sb.append("- If no tool is needed, provide the Final Answer directly.\n");

        return sb.toString().trim();
    }

    /**
     * 构建用户提示词（包含任务和历史）
     */
    public static String buildUserPrompt(String task, String history) {
        return buildUserPrompt(task, history, null);
    }

    /**
     * 构建用户提示词（支持记忆上下文）
     */
    public static String buildUserPrompt(String task, String history, String memoryContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task).append("\n\n");
        if (history != null && !history.isBlank()) {
            sb.append("Previous steps:\n").append(history).append("\n\n");
        }
        sb.append("Now continue with your next Thought.");
        return sb.toString().trim();
    }

    /**
     * 将工具列表格式化为文本描述
     */
    private static String formatTools(List<Tool> tools) {
        if (tools == null || tools.isEmpty()) {
            return "(No tools available)";
        }
        StringBuilder sb = new StringBuilder();
        for (Tool tool : tools) {
            sb.append("- ").append(tool.getName()).append(": ")
              .append(tool.getDescription());
            if (tool.getParameterSchema() != null) {
                sb.append(" (parameters: ")
                  .append(JSON.toJSONString(tool.getParameterSchema()))
                  .append(")");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }
}
