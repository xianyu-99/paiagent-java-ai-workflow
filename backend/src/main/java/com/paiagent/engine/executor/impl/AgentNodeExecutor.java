package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.agent.AgentState;
import com.paiagent.engine.agent.ReasoningEngine;
import com.paiagent.engine.agent.ReasoningResult;
import com.paiagent.engine.agent.memory.AgentMemoryService;
import com.paiagent.engine.agent.tool.Tool;
import com.paiagent.engine.agent.tool.ToolRegistry;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.llm.ChatClientFactory;
import com.paiagent.engine.llm.LLMProviderRegistry;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.reference.WorkflowReferenceResolver;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.service.LLMGlobalConfigService;
import com.paiagent.service.SkillEvolutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

@Slf4j
@Component
public class AgentNodeExecutor implements NodeExecutor {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private List<ReasoningEngine> reasoningEngines;

    @Autowired
    private ChatClientFactory chatClientFactory;

    @Autowired
    private LLMGlobalConfigService llmGlobalConfigService;

    @Autowired
    private AgentMemoryService agentMemoryService;

    @Autowired(required = false)
    private SkillEvolutionService skillEvolutionService;

    /**
     * Agent节点配置
     */
    private static class AgentNodeConfig {
        String reasoningMode = "react";
        List<String> tools = new ArrayList<>();
        int maxIterations = 5;
        String systemPrompt;
        String taskTemplate;
        List<Map<String, Object>> inputParams = new ArrayList<>();
        String provider;
        String apiUrl;
        String apiKey;
        String model;
        Double temperature;
        Long configId;
        Long knowledgeBaseId;
        boolean enableExecutionMemory;
        int memoryTopK = 3;
        double memoryMinScore = 0.5;
        String collaborationMode = "single";
        boolean reviewerEnabled = true;
        String skillName;
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        String nodeId = node.getId();

        // 1. 提取配置
        AgentNodeConfig config = extractConfig(node);
        validateConfig(config);

        log.info("Agent 节点配置 - 推理模式: {}, 最大迭代: {}, 工具数: {}, Model: {}, 知识库: {}, 执行记忆: {}",
                config.reasoningMode, config.maxIterations, config.tools.size(), config.model,
                config.knowledgeBaseId, config.enableExecutionMemory);

        // 2. 解析可用工具
        List<Tool> availableTools = resolveTools(config);
        log.debug("Agent 可用工具: {}", availableTools.stream().map(Tool::getName).toList());

        // 3. 解析推理引擎
        ReasoningEngine reasoningEngine = reasoningEngines.stream()
                .filter(e -> e.getMode().equals(config.reasoningMode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "不支持的推理模式: " + config.reasoningMode + "，可用模式: " +
                                reasoningEngines.stream().map(ReasoningEngine::getMode).toList()));

        // 4. 构建任务字符串
        String task = buildTask(config, input);
        log.debug("Agent 任务: {}", summarizeForLog(task));

        // 5. 创建 ChatClient
        ChatClient chatClient = chatClientFactory.createClient(
                config.provider, config.apiUrl, config.apiKey, config.model, config.temperature);

        // 6. 创建 AgentState
        String sessionId = UUID.randomUUID().toString();
        AgentState state = new AgentState(sessionId, task, config.maxIterations);
        state.setSystemPrompt(config.systemPrompt);

        // 6.5 检索长期记忆上下文
        Long flowId = input != null ? extractFlowId(input) : null;
        AgentMemoryService.MemoryContextResult memoryContextResult = null;
        if (config.knowledgeBaseId != null || config.enableExecutionMemory) {
            try {
                memoryContextResult = agentMemoryService.buildMemoryContextWithMetadata(
                        task, config.knowledgeBaseId, flowId,
                        config.enableExecutionMemory, config.memoryTopK, config.memoryMinScore);
                if (memoryContextResult != null) {
                    applyMemoryContext(state, memoryContextResult);
                    log.debug("Agent 记忆上下文注入成功，长度: {}", memoryContextResult.content().length());
                }
            } catch (Exception e) {
                log.warn("Agent 记忆上下文检索失败，继续执行: {}", e.getMessage());
            }
        }

        // 7. 执行 ReAct 循环
        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    nodeId, "agent", "开始推理...", Map.of("iteration", 0)));
        }

        if ("planner_worker_reviewer".equalsIgnoreCase(config.collaborationMode)) {
            AgentRunResult runResult = executeCollaborative(
                    nodeId, sessionId, task, config, reasoningEngine, availableTools, chatClient,
                    memoryContextResult, progressCallback);
            Map<String, Object> output = buildOutput(runResult.finalState());
            output.put("collaborationMode", config.collaborationMode);
            output.put("agentTrace", runResult.trace());
            appendTracePromptCacheTotals(output, runResult.trace());
            output.put("skillEvolutionCandidateRecorded",
                    recordSkillEvolutionCandidate(config, runResult.finalState()));
            log.info("Agent collaborative execution completed - stages={}, finalFinished={}",
                    runResult.trace().size(), runResult.finalState().isFinished());
            return output;
        }

        int iteration = 0;
        while (iteration < config.maxIterations) {
            iteration++;
            log.debug("Agent 第 {} 轮推理开始", iteration);

            // 执行推理
            ReasoningResult result = reasoningEngine.reason(state, availableTools, chatClient);

            // 将LLM的思考添加到状态（必须在处理结果前，因为后续步骤会读取历史）
            state.addThought(iteration, result.getThought() != null ? result.getThought() : "");

            log.debug("Agent 推理结果 - 类型: {}, thought: {}",
                    result.getType(), summarizeForLog(result.getThought()));

            // 发送进度事件
            if (progressCallback != null) {
                Map<String, Object> progressData = new HashMap<>();
                progressData.put("iteration", iteration);
                progressData.put("thought", result.getThought());
                progressData.put("type", result.getType().name());
                if (result.getType() == ReasoningResult.DecisionType.ACTION) {
                    progressData.put("action", result.getAction());
                    progressData.put("actionInput", result.getActionInput());
                } else if (result.getType() == ReasoningResult.DecisionType.FINAL_ANSWER) {
                    progressData.put("finalAnswer", result.getFinalAnswer());
                } else if (result.getType() == ReasoningResult.DecisionType.ERROR) {
                    progressData.put("error", result.getErrorMessage());
                }
                progressCallback.accept(ExecutionEvent.nodeProgress(
                        nodeId, "agent", "推理第 " + iteration + " 轮", progressData));
            }

            // 处理结果
            switch (result.getType()) {
                case FINAL_ANSWER -> {
                    state.finish(result.getFinalAnswer());
                    log.info("Agent 完成，最终答案: {}", summarizeForLog(result.getFinalAnswer()));
                    if (progressCallback != null) {
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                                nodeId, "agent", "推理完成",
                                Map.of("iteration", iteration, "finalAnswer", result.getFinalAnswer())));
                    }
                }
                case ERROR -> {
                    state.fail(result.getErrorMessage());
                    log.warn("Agent 推理错误: {}", result.getErrorMessage());
                }
                case ACTION -> {
                    state.addAction(result.getAction(), result.getActionInput());

                    // 查找并执行工具
                    Optional<Tool> toolOpt = availableTools.stream()
                            .filter(t -> t.getName().equals(result.getAction()))
                            .findFirst();

                    String observation;
                    if (toolOpt.isEmpty()) {
                        observation = "Error: Tool not found: " + result.getAction();
                        log.warn("Agent 调用未找到的工具: {}", result.getAction());
                    } else {
                        Tool tool = toolOpt.get();
                        try {
                            Map<String, Object> arguments = parseActionInput(result.getActionInput());
                            observation = tool.execute(arguments);
                            log.debug("Agent 工具 {} 执行结果: {}", tool.getName(), summarizeForLog(observation));
                        } catch (Exception e) {
                            observation = "Error: " + e.getMessage();
                            log.warn("Agent 工具 {} 执行失败: {}", tool.getName(), e.getMessage());
                        }
                    }
                    state.addObservation(observation);

                    // 发送工具执行进度
                    if (progressCallback != null) {
                        Map<String, Object> actionData = new HashMap<>();
                        actionData.put("iteration", iteration);
                        actionData.put("action", result.getAction());
                        actionData.put("actionInput", result.getActionInput());
                        actionData.put("observation", observation);
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                                nodeId, "agent", "执行工具 " + result.getAction(), actionData));
                    }
                }
                case THOUGHT -> {
                    // 纯思考，无行动，添加空观察以继续循环
                    state.addObservation("(no action taken)");
                }
            }

            // 检查是否已结束
            if (state.isFinished()) {
                break;
            }

            // 检查是否达到最大迭代次数
            if (state.hasReachedMaxIterations()) {
                state.fail("Maximum iterations reached");
                log.warn("Agent 达到最大迭代次数限制: {}", config.maxIterations);
                break;
            }
        }

        // 8. 构建输出
        Map<String, Object> output = buildOutput(state);
        output.put("skillEvolutionCandidateRecorded", recordSkillEvolutionCandidate(config, state));
        log.info("Agent 节点执行完成 - 迭代次数: {}, 是否成功: {}, 记忆上下文: {}",
                state.getCurrentIteration(), state.isFinished(),
                state.getMemoryContext() != null ? "已注入 (" + state.getMemoryContext().length() + " 字符)" : "无");
        log.debug("Agent 节点输出: {}", summarizeForLog(output));

        return output;
    }

    /**
     * 从节点数据中提取配置
     */
    private AgentRunResult executeCollaborative(
            String nodeId,
            String sessionId,
            String task,
            AgentNodeConfig config,
            ReasoningEngine reasoningEngine,
            List<Tool> availableTools,
            ChatClient chatClient,
            AgentMemoryService.MemoryContextResult memoryContextResult,
            Consumer<ExecutionEvent> progressCallback
    ) {
        List<Map<String, Object>> trace = new ArrayList<>();

        AgentState planner = runAgentLoop(
                nodeId,
                sessionId + ":planner",
                "Plan the work for this task. Return a concise plan.\nTask: " + task,
                appendRole(config.systemPrompt, "planner"),
                config,
                reasoningEngine,
                availableTools,
                chatClient,
                memoryContextResult,
                "planner",
                progressCallback
        );
        trace.add(buildStageTrace("planner", planner));

        String plan = planner.getFinalAnswer() != null ? planner.getFinalAnswer() : planner.getErrorMessage();
        AgentState worker = runAgentLoop(
                nodeId,
                sessionId + ":worker",
                "Solve the task using the plan.\nTask: " + task + "\nPlan: " + nullToEmpty(plan),
                appendRole(config.systemPrompt, "worker"),
                config,
                reasoningEngine,
                availableTools,
                chatClient,
                memoryContextResult,
                "worker",
                progressCallback
        );
        trace.add(buildStageTrace("worker", worker));

        AgentState finalState = worker;
        if (config.reviewerEnabled) {
            String workerAnswer = worker.getFinalAnswer() != null ? worker.getFinalAnswer() : worker.getErrorMessage();
            AgentState reviewer = runAgentLoop(
                    nodeId,
                    sessionId + ":reviewer",
                    "Review the answer against the task and memory. If it is acceptable, return the improved final answer.\n"
                            + "Task: " + task + "\nPlan: " + nullToEmpty(plan) + "\nAnswer: " + nullToEmpty(workerAnswer),
                    appendRole(config.systemPrompt, "reviewer"),
                    config,
                    reasoningEngine,
                    availableTools,
                    chatClient,
                    memoryContextResult,
                    "reviewer",
                    progressCallback
            );
            trace.add(buildStageTrace("reviewer", reviewer));
            if (!isBlank(reviewer.getFinalAnswer())) {
                finalState = reviewer;
            } else if (!isBlank(reviewer.getErrorMessage())) {
                log.warn("Agent reviewer failed; preserving worker result. error={}", reviewer.getErrorMessage());
            }
        }

        return new AgentRunResult(finalState, trace);
    }

    private AgentState runAgentLoop(
            String nodeId,
            String sessionId,
            String task,
            String systemPrompt,
            AgentNodeConfig config,
            ReasoningEngine reasoningEngine,
            List<Tool> availableTools,
            ChatClient chatClient,
            AgentMemoryService.MemoryContextResult memoryContextResult,
            String stage,
            Consumer<ExecutionEvent> progressCallback
    ) {
        AgentState state = new AgentState(sessionId, task, config.maxIterations);
        state.setSystemPrompt(systemPrompt);
        applyMemoryContext(state, memoryContextResult);

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    nodeId, "agent", "agent stage started",
                    Map.of("stage", stage, "iteration", 0)));
        }

        int iteration = 0;
        while (iteration < config.maxIterations) {
            iteration++;
            ReasoningResult result = reasoningEngine.reason(state, availableTools, chatClient);
            state.addThought(iteration, result.getThought() != null ? result.getThought() : "");

            if (progressCallback != null) {
                Map<String, Object> progressData = new HashMap<>();
                progressData.put("stage", stage);
                progressData.put("iteration", iteration);
                progressData.put("type", result.getType().name());
                progressCallback.accept(ExecutionEvent.nodeProgress(
                        nodeId, "agent", "agent stage progress", progressData));
            }

            switch (result.getType()) {
                case FINAL_ANSWER -> state.finish(result.getFinalAnswer());
                case ERROR -> state.fail(result.getErrorMessage());
                case ACTION -> {
                    state.addAction(result.getAction(), result.getActionInput());
                    String observation = executeToolAction(result, availableTools);
                    state.addObservation(observation);
                }
                case THOUGHT -> state.addObservation("(no action taken)");
            }

            if (state.isFinished()) {
                break;
            }
            if (state.hasReachedMaxIterations()) {
                state.fail("Maximum iterations reached");
                break;
            }
        }
        return state;
    }

    private void applyMemoryContext(AgentState state, AgentMemoryService.MemoryContextResult memoryContextResult) {
        if (state == null || memoryContextResult == null) {
            return;
        }
        state.setMemoryContext(memoryContextResult.content());
        state.setMemoryCompressed(memoryContextResult.compressed());
        state.setMemoryCompressionRatio(memoryContextResult.compressionRatio());
    }

    private String executeToolAction(ReasoningResult result, List<Tool> availableTools) {
        Optional<Tool> toolOpt = availableTools.stream()
                .filter(tool -> tool.getName().equals(result.getAction()))
                .findFirst();
        if (toolOpt.isEmpty()) {
            return "Error: Tool not found: " + result.getAction();
        }
        try {
            return toolOpt.get().execute(parseActionInput(result.getActionInput()));
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String appendRole(String systemPrompt, String role) {
        String base = systemPrompt == null ? "" : systemPrompt.trim();
        String rolePrompt = switch (role) {
            case "planner" -> "You are the planner agent. Decompose the task into concise steps.";
            case "worker" -> "You are the worker agent. Execute the plan and produce the answer.";
            case "reviewer" -> "You are the reviewer agent. Check factual support, citations, and completeness.";
            default -> "You are an agent.";
        };
        return base.isBlank() ? rolePrompt : base + "\n\n" + rolePrompt;
    }

    private Map<String, Object> buildStageTrace(String stage, AgentState state) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stage", stage);
        item.put("finished", state.isFinished());
        item.put("iterations", state.getCurrentIteration());
        item.put("finalAnswer", state.getFinalAnswer());
        item.put("error", state.getErrorMessage());
        item.put("promptCacheHits", state.getPromptCacheHits());
        item.put("promptCacheMisses", state.getPromptCacheMisses());
        item.put("promptCacheEstimatedSavedChars", state.getPromptCacheEstimatedSavedChars());
        return item;
    }

    private void appendTracePromptCacheTotals(Map<String, Object> output, List<Map<String, Object>> trace) {
        if (trace == null || trace.isEmpty()) {
            return;
        }
        output.put("promptCacheHits", sumTraceNumber(trace, "promptCacheHits"));
        output.put("promptCacheMisses", sumTraceNumber(trace, "promptCacheMisses"));
        output.put("promptCacheEstimatedSavedChars", sumTraceNumber(trace, "promptCacheEstimatedSavedChars"));
    }

    private long sumTraceNumber(List<Map<String, Object>> trace, String key) {
        return trace.stream()
                .map(stage -> stage.get(key))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .mapToLong(Number::longValue)
                .sum();
    }

    private boolean recordSkillEvolutionCandidate(AgentNodeConfig config, AgentState state) {
        if (skillEvolutionService == null || isBlank(config.skillName)) {
            return false;
        }

        String error = state.getErrorMessage();
        String finalAnswer = state.getFinalAnswer();
        boolean failed = !isBlank(error);
        boolean blankAnswer = isBlank(finalAnswer) && !failed;
        boolean memoryCompressed = state.isMemoryCompressed();
        SkillEvolutionIssue answerQualityIssue = detectAnswerQualityIssue(finalAnswer);
        if (!failed && !blankAnswer && answerQualityIssue == null && !memoryCompressed) {
            return false;
        }

        String feedbackType = failed ? "AGENT_FAILURE" : blankAnswer ? "BLANK_ANSWER"
                : answerQualityIssue != null ? answerQualityIssue.feedbackType()
                : "CONTEXT_COMPRESSED";
        String summary = failed ? error : blankAnswer ? "Agent returned blank answer."
                : answerQualityIssue != null ? answerQualityIssue.summary()
                : "Memory context was compressed; review whether the skill should request narrower evidence.";
        String proposedPatch = "Review skill `" + config.skillName + "` for task: "
                + summarizeForLog(state.getTask());
        try {
            skillEvolutionService.recordCandidate(
                    config.skillName,
                    "AGENT_EXECUTION",
                    null,
                    feedbackType,
                    summary,
                    proposedPatch
            );
            return true;
        } catch (Exception e) {
            log.warn("Failed to record skill evolution candidate: {}", e.getMessage());
            return false;
        }
    }

    private SkillEvolutionIssue detectAnswerQualityIssue(String finalAnswer) {
        Map<String, Object> answer = parseStructuredAnswer(finalAnswer);
        if (answer == null || !looksLikeQualityAwareAnswer(answer)) {
            return null;
        }

        Double confidence = toDouble(answer.get("confidence"));
        if (confidence != null && confidence < 0.5d) {
            return new SkillEvolutionIssue(
                    "LOW_CONFIDENCE",
                    "Agent returned low confidence answer (confidence=" + confidence + ")."
            );
        }

        if (!isBlank(trimString(answer.get("answer"))) && isCitationMissing(answer.get("citations"))) {
            return new SkillEvolutionIssue(
                    "MISSING_CITATION",
                    "Agent returned a structured answer without citations."
            );
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseStructuredAnswer(String finalAnswer) {
        String normalized = normalizeStructuredAnswerText(finalAnswer);
        if (isBlank(normalized) || !normalized.startsWith("{") || !normalized.endsWith("}")) {
            return null;
        }
        try {
            Object parsed = JSON.parseObject(normalized, Map.class);
            return parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        } catch (JSONException e) {
            log.debug("Final answer is not structured JSON for skill evolution detection: {}", summarizeForLog(finalAnswer));
            return null;
        }
    }

    private String normalizeStructuredAnswerText(String finalAnswer) {
        if (finalAnswer == null) {
            return null;
        }
        String trimmed = finalAnswer.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed.trim();
    }

    private boolean looksLikeQualityAwareAnswer(Map<String, Object> answer) {
        return answer.containsKey("answer")
                || answer.containsKey("citations")
                || answer.containsKey("confidence")
                || answer.containsKey("resolved")
                || answer.containsKey("nextAction")
                || answer.containsKey("ticketSummary")
                || answer.containsKey("escalationReason");
    }

    private boolean isCitationMissing(Object citations) {
        if (citations == null) {
            return true;
        }
        if (citations instanceof Collection<?> collection) {
            return collection.isEmpty();
        }
        if (citations instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return citations.toString().isBlank();
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record AgentRunResult(AgentState finalState, List<Map<String, Object>> trace) {
    }

    private record SkillEvolutionIssue(String feedbackType, String summary) {
    }

    @SuppressWarnings("unchecked")
    private AgentNodeConfig extractConfig(WorkflowNode node) {
        Map<String, Object> data = node.getData();
        AgentNodeConfig config = new AgentNodeConfig();

        // 优先使用全局配置
        Long configId = data.get("configId") != null
                ? ((Number) data.get("configId")).longValue()
                : null;

        if (configId != null) {
            LLMGlobalConfig globalConfig = llmGlobalConfigService.getDecryptedById(configId);
            if (globalConfig != null) {
                config.apiUrl = globalConfig.getApiUrl();
                config.apiKey = globalConfig.getApiKey();
                config.model = globalConfig.getModel();
                config.temperature = globalConfig.getTemperature() != null
                        ? globalConfig.getTemperature().doubleValue()
                        : 0.7;
                config.provider = LLMProviderRegistry.normalizeProvider(trimString(globalConfig.getProvider()));
                log.info("Agent 使用全局配置: {}", globalConfig.getConfigName());
            } else {
                log.warn("Agent 全局配置不存在: {}", configId);
                applyNodeLevelLLMConfig(config, data);
            }
        } else {
            applyNodeLevelLLMConfig(config, data);
        }

        config.configId = configId;
        if (isBlank(config.provider)) {
            config.provider = LLMProviderRegistry.normalizeProvider(trimString(data.get("provider")));
        }
        config.apiUrl = LLMProviderRegistry.resolveBaseUrl(config.provider, config.apiUrl);

        // Agent 特有配置
        config.reasoningMode = trimString(data.get("reasoningMode"));
        if (isBlank(config.reasoningMode)) {
            config.reasoningMode = "react";
        }

        Object toolsObj = data.get("tools");
        if (toolsObj instanceof List<?> toolList) {
            for (Object toolName : toolList) {
                if (toolName != null) {
                    config.tools.add(toolName.toString());
                }
            }
        }

        Object maxIterObj = data.get("maxIterations");
        if (maxIterObj instanceof Number num) {
            config.maxIterations = Math.min(Math.max(num.intValue(), 1), 20);
        } else {
            config.maxIterations = 5;
        }

        config.systemPrompt = trimString(data.get("systemPrompt"));
        config.taskTemplate = trimString(data.get("taskTemplate"));
        Object inputParamsObj = data.get("inputParams");
        if (inputParamsObj instanceof List<?> inputParamList) {
            for (Object inputParam : inputParamList) {
                if (inputParam instanceof Map<?, ?> map) {
                    Map<String, Object> normalized = new HashMap<>();
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (entry.getKey() != null) {
                            normalized.put(entry.getKey().toString(), entry.getValue());
                        }
                    }
                    config.inputParams.add(normalized);
                }
            }
        }

        // 记忆相关配置
        Object knowledgeBaseIdObj = data.get("knowledgeBaseId");
        if (knowledgeBaseIdObj instanceof Number num) {
            config.knowledgeBaseId = num.longValue();
        }
        Object enableExecutionMemoryObj = data.get("enableExecutionMemory");
        if (enableExecutionMemoryObj instanceof Boolean b) {
            config.enableExecutionMemory = b;
        } else if (enableExecutionMemoryObj != null) {
            config.enableExecutionMemory = Boolean.parseBoolean(enableExecutionMemoryObj.toString());
        }
        Object memoryTopKObj = data.get("memoryTopK");
        if (memoryTopKObj instanceof Number num) {
            config.memoryTopK = Math.min(Math.max(num.intValue(), 1), 10);
        } else {
            config.memoryTopK = 3;
        }
        Object memoryMinScoreObj = data.get("memoryMinScore");
        if (memoryMinScoreObj instanceof Number num) {
            config.memoryMinScore = Math.min(Math.max(num.doubleValue(), 0.0), 1.0);
        } else {
            config.memoryMinScore = 0.5;
        }

        String collaborationMode = trimString(data.get("collaborationMode"));
        if ("planner_worker_reviewer".equalsIgnoreCase(collaborationMode)) {
            config.collaborationMode = "planner_worker_reviewer";
        } else {
            config.collaborationMode = "single";
        }
        Object reviewerEnabledObj = data.get("reviewerEnabled");
        if (reviewerEnabledObj instanceof Boolean b) {
            config.reviewerEnabled = b;
        } else if (reviewerEnabledObj != null) {
            config.reviewerEnabled = Boolean.parseBoolean(reviewerEnabledObj.toString());
        }
        config.skillName = trimString(data.get("skillName"));

        return config;
    }

    private void applyNodeLevelLLMConfig(AgentNodeConfig config, Map<String, Object> data) {
        config.provider = LLMProviderRegistry.normalizeProvider(trimString(data.get("provider")));
        config.apiUrl = trimString(data.get("apiUrl"));
        config.apiKey = trimString(data.get("apiKey"));
        config.model = trimString(data.get("model"));
        Object tempObj = data.get("temperature");
        config.temperature = tempObj != null
                ? ((Number) tempObj).doubleValue()
                : 0.7;
    }

    private void validateConfig(AgentNodeConfig config) {
        if (isBlank(config.provider)) {
            throw new IllegalArgumentException("Agent 节点缺少有效的提供商配置，请先选择供应商或全局配置");
        }
        if (isBlank(config.apiUrl) || isBlank(config.apiKey) || isBlank(config.model)) {
            throw new IllegalArgumentException("Agent 节点缺少有效的模型配置，请检查全局配置或节点配置");
        }
    }

    private List<Tool> resolveTools(AgentNodeConfig config) {
        if (config.tools == null || config.tools.isEmpty()) {
            return toolRegistry.getAllTools();
        }
        List<String> missingTools = config.tools.stream()
                .filter(toolName -> !toolRegistry.hasTool(toolName))
                .toList();
        if (!missingTools.isEmpty()) {
            throw new IllegalArgumentException("Agent node configured unknown tools: " + missingTools);
        }
        return toolRegistry.getToolsByNames(config.tools);
    }

    private String buildTask(AgentNodeConfig config, Map<String, Object> input) {
        if (!isBlank(config.taskTemplate)) {
            String task = config.taskTemplate;
            // 替换 {{input}} 占位符
            Object inputValue = input != null ? input.get("input") : null;
            String inputStr = inputValue != null ? inputValue.toString() : "";
            task = task.replace("{{input}}", inputStr);
            for (Map.Entry<String, String> entry : resolveInputParamValues(config.inputParams, input).entrySet()) {
                task = task.replace("{{" + entry.getKey() + "}}", entry.getValue());
            }

            // 替换其他引用参数
            if (input != null) {
                for (Map.Entry<String, Object> entry : input.entrySet()) {
                    String placeholder = "{{" + entry.getKey() + "}}";
                    String value = entry.getValue() != null ? entry.getValue().toString() : "";
                    task = task.replace(placeholder, value);
                }
            }
            return task;
        }

        // 默认使用 input 字段
        String taskFromParams = buildTaskFromInputParams(config.inputParams, input);
        if (!isBlank(taskFromParams)) {
            return taskFromParams;
        }

        if (input != null && input.get("output") != null) {
            return input.get("output").toString();
        }

        if (input != null && input.get("input") != null) {
            return input.get("input").toString();
        }
        return "";
    }

    private String buildTaskFromInputParams(List<Map<String, Object>> inputParams, Map<String, Object> input) {
        Map<String, String> values = resolveInputParamValues(inputParams, input);
        if (values.isEmpty()) {
            return "";
        }

        for (String preferredName : List.of("task", "input", "question", "prompt")) {
            String preferredValue = values.get(preferredName);
            if (!isBlank(preferredValue)) {
                return preferredValue;
            }
        }

        if (values.size() == 1) {
            return values.values().iterator().next();
        }

        return values.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private Map<String, String> resolveInputParamValues(List<Map<String, Object>> inputParams, Map<String, Object> input) {
        Map<String, String> values = new LinkedHashMap<>();
        if (inputParams == null || inputParams.isEmpty()) {
            return values;
        }

        for (Map<String, Object> param : inputParams) {
            String name = trimString(param.get("name"));
            if (isBlank(name)) {
                continue;
            }

            String value = resolveAgentInputParam(param, input);
            if (!isBlank(value)) {
                values.put(name, value);
            }
        }
        return values;
    }

    private String resolveAgentInputParam(Map<String, Object> param, Map<String, Object> input) {
        String type = trimString(param.get("type"));
        if ("input".equals(type)) {
            return trimString(param.get("value"));
        }
        if ("reference".equals(type)) {
            Object value = WorkflowReferenceResolver.resolve(trimString(param.get("referenceNode")), input);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    /**
     * 解析 actionInput JSON 为参数 Map
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseActionInput(String actionInput) {
        if (actionInput == null || actionInput.isBlank()) {
            return new HashMap<>();
        }
        try {
            Object parsed = JSON.parseObject(actionInput, Map.class);
            if (parsed instanceof Map) {
                return (Map<String, Object>) parsed;
            }
        } catch (JSONException e) {
            log.debug("Action input is not valid JSON, using raw string: {}", actionInput);
        }
        // 非 JSON，包装为 raw 字段
        Map<String, Object> fallback = new HashMap<>();
        fallback.put("raw", actionInput);
        return fallback;
    }

    /**
     * 构建输出结果
     */
    private Map<String, Object> buildOutput(AgentState state) {
        Map<String, Object> output = new HashMap<>();
        String resultText = state.getFinalAnswer() != null ? state.getFinalAnswer() : state.getErrorMessage();
        output.put("output", resultText);
        output.put("finalAnswer", state.getFinalAnswer());
        output.put("iterations", state.getCurrentIteration());
        output.put("finished", state.isFinished());
        output.put("error", state.getErrorMessage());
        output.put("thoughts", buildThoughtsList(state));
        output.put("memoryContext", state.getMemoryContext());
        output.put("memoryCompressed", state.isMemoryCompressed());
        output.put("memoryCompressionRatio", state.getMemoryCompressionRatio());
        output.put("promptCacheHits", state.getPromptCacheHits());
        output.put("promptCacheMisses", state.getPromptCacheMisses());
        output.put("promptCacheEstimatedSavedChars", state.getPromptCacheEstimatedSavedChars());
        output.put("collaborationMode", state.getSessionId() != null && state.getSessionId().contains(":")
                ? "planner_worker_reviewer"
                : "single");
        output.put("inputTokens", 0);
        output.put("outputTokens", 0);
        output.put("totalTokens", 0);
        output.put("tokens", 0);
        return output;
    }

    /**
     * 将 AgentState 的步骤列表转换为 Map 列表
     */
    private List<Map<String, Object>> buildThoughtsList(AgentState state) {
        List<Map<String, Object>> thoughts = new ArrayList<>();
        for (AgentState.Step step : state.getSteps()) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("iteration", step.getIteration());
            stepMap.put("thought", step.getThought());
            stepMap.put("action", step.getAction());
            stepMap.put("actionInput", step.getActionInput());
            stepMap.put("observation", step.getObservation());
            thoughts.add(stepMap);
        }
        return thoughts;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimString(Object value) {
        return value != null ? value.toString().trim() : null;
    }

    private String summarizeForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        int maxLength = 1000;
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated)";
    }

    private Long extractFlowId(Map<String, Object> input) {
        Object flowIdObj = input.get("__executionFlowId__");
        if (flowIdObj instanceof Number num) {
            return num.longValue();
        }
        return null;
    }

    @Override
    public String getSupportedNodeType() {
        return "agent";
    }
}
