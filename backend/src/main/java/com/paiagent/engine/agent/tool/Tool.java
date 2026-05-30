package com.paiagent.engine.agent.tool;

import java.util.Map;

/**
 * Agent可调用的工具接口
 */
public interface Tool {

    /**
     * 工具唯一标识名（如 "search", "calculator"）
     */
    String getName();

    /**
     * 工具描述（用于LLM理解何时调用此工具）
     */
    String getDescription();

    /**
     * 执行工具
     *
     * @param arguments 工具参数（由LLM生成）
     * @return 工具执行结果（作为Observation返回给LLM）
     */
    String execute(Map<String, Object> arguments) throws Exception;

    /**
     * 获取工具参数Schema（JSON Schema格式，用于LLM理解参数结构）
     * 返回描述工具所需参数的JSON Schema对象
     */
    Map<String, Object> getParameterSchema();
}
