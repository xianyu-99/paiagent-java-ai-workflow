package com.paiagent.engine.agent;

import com.paiagent.engine.agent.tool.Tool;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

/**
 * 推理引擎接口
 * 负责与LLM交互，根据当前状态和可用工具做出决策
 */
public interface ReasoningEngine {

    /**
     * 执行一轮推理
     * @param state 当前Agent状态
     * @param availableTools 当前可用的工具列表
     * @param chatClient Spring AI ChatClient（已配置好系统提示）
     * @return 推理结果，包含决策类型和具体内容
     */
    ReasoningResult reason(AgentState state, List<Tool> availableTools, ChatClient chatClient);

    /**
     * 获取引擎支持的推理模式名称
     */
    String getMode();
}
