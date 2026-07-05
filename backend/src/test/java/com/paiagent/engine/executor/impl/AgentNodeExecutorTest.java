package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.agent.AgentState;
import com.paiagent.engine.agent.ReasoningEngine;
import com.paiagent.engine.agent.ReasoningResult;
import com.paiagent.engine.agent.tool.Tool;
import com.paiagent.engine.agent.tool.ToolRegistry;
import com.paiagent.engine.llm.ChatClientFactory;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.service.LLMGlobalConfigService;
import com.paiagent.service.SkillEvolutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentNodeExecutorTest {

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ChatClientFactory chatClientFactory;

    @Mock
    LLMGlobalConfigService llmGlobalConfigService;

    @Mock
    SkillEvolutionService skillEvolutionService;

    @Mock
    ChatClient chatClient;

    @InjectMocks
    AgentNodeExecutor executor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(executor, "reasoningEngines", List.of(reasoningEngine));
    }

    private WorkflowNode agentNode(Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId("agent-1");
        node.setType("agent");
        node.setData(data);
        return node;
    }

    private Map<String, Object> validConfig() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "openai");
        data.put("apiUrl", "https://api.openai.com");
        data.put("apiKey", "test-key");
        data.put("model", "gpt-4");
        data.put("reasoningMode", "react");
        data.put("maxIterations", 5);
        data.put("tools", Collections.emptyList());
        return data;
    }

    @Test
    void shouldThrowOnUnsupportedReasoningMode() {
        Map<String, Object> data = validConfig();
        data.put("reasoningMode", "unsupported");

        when(reasoningEngine.getMode()).thenReturn("react");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(agentNode(data), Map.of()));
        assertTrue(exception.getMessage().contains("不支持的推理模式"));
    }

    @Test
    void shouldThrowOnMissingLlmConfig() {
        Map<String, Object> data = new HashMap<>();
        data.put("provider", "");
        data.put("apiUrl", "");
        data.put("apiKey", "");
        data.put("model", "");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(agentNode(data), Map.of()));
        assertTrue(exception.getMessage().contains("缺少有效的提供商配置"));
    }

    @Test
    void shouldExecuteToolAndReturnFinalAnswer() throws Exception {
        Map<String, Object> data = validConfig();
        WorkflowNode node = agentNode(data);

        Tool mockTool = mock(Tool.class);
        when(mockTool.getName()).thenReturn("calculator");
        when(mockTool.execute(anyMap())).thenReturn("4");

        when(toolRegistry.getAllTools()).thenReturn(List.of(mockTool));
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");

        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(
                        ReasoningResult.action("I need to calculate", "calculator", "{\"expr\": \"2+2\"}"),
                        ReasoningResult.finalAnswer("I have the answer", "The answer is 4")
                );

        Map<String, Object> output = executor.execute(node, Map.of("input", "What is 2+2?"));

        assertEquals("The answer is 4", output.get("output"));
        assertEquals("The answer is 4", output.get("finalAnswer"));
        assertTrue((Boolean) output.get("finished"));
    }

    @Test
    void shouldHandleToolNotFound() throws Exception {
        Map<String, Object> data = validConfig();
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");

        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(
                        ReasoningResult.action("I need to search", "search", "{\"query\": \"test\"}"),
                        ReasoningResult.finalAnswer("I give up", "No answer")
                );

        Map<String, Object> output = executor.execute(node, Map.of("input", "Search for something"));

        assertEquals("No answer", output.get("output"));
        assertTrue((Boolean) output.get("finished"));
    }

    @Test
    void shouldFailOnMaxIterations() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("maxIterations", 2);
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");

        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(
                        ReasoningResult.action("Step 1", "tool1", "{}"),
                        ReasoningResult.action("Step 2", "tool2", "{}")
                );

        Map<String, Object> output = executor.execute(node, Map.of("input", "Complex task"));

        assertNotNull(output.get("error"));
        assertEquals("Maximum iterations reached", output.get("error"));
        assertTrue((Boolean) output.get("finished"));
    }

    @Test
    void shouldEmitProgressEvents() throws Exception {
        Map<String, Object> data = validConfig();
        WorkflowNode node = agentNode(data);

        List<ExecutionEvent> events = new ArrayList<>();
        Consumer<ExecutionEvent> progressCallback = events::add;

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");

        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        executor.execute(node, Map.of("input", "Test"), progressCallback);

        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> "NODE_PROGRESS".equals(e.getEventType())));
    }

    @Test
    void shouldIncludeThoughtsInOutput() throws Exception {
        Map<String, Object> data = validConfig();
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");

        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("My thought", "Final answer"));

        Map<String, Object> output = executor.execute(node, Map.of("input", "Test"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thoughts = (List<Map<String, Object>>) output.get("thoughts");
        assertNotNull(thoughts);
        assertEquals(1, thoughts.size());
        assertEquals("My thought", thoughts.get(0).get("thought"));
    }

    @Test
    void shouldUseGlobalConfigWhenConfigIdProvided() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("configId", 1L);
        data.remove("apiUrl");
        data.remove("apiKey");
        data.remove("model");
        WorkflowNode node = agentNode(data);

        LLMGlobalConfig globalConfig = new LLMGlobalConfig();
        globalConfig.setApiUrl("https://global.api.com");
        globalConfig.setApiKey("global-key");
        globalConfig.setModel("global-model");
        globalConfig.setProvider("openai");
        globalConfig.setTemperature(new java.math.BigDecimal("0.5"));

        when(llmGlobalConfigService.getDecryptedById(1L)).thenReturn(globalConfig);
        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");

        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        Map<String, Object> output = executor.execute(node, Map.of("input", "Test"));

        assertEquals("Answer", output.get("output"));
        verify(chatClientFactory).createClient(eq("openai"), eq("https://global.api.com"),
                eq("global-key"), eq("global-model"), any());
    }

    @Test
    void shouldBuildTaskFromInputParamsAndCarrySystemPrompt() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("systemPrompt", "Answer with strict JSON.");
        data.put("inputParams", List.of(Map.of(
                "name", "task",
                "type", "reference",
                "referenceNode", "input"
        )));
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        executor.execute(node, Map.of("input", "Summarize the ticket"));

        verify(reasoningEngine).reason(argThat(state ->
                state != null
                        && "Summarize the ticket".equals(state.getTask())
                        && "Answer with strict JSON.".equals(state.getSystemPrompt())
        ), anyList(), eq(chatClient));
    }

    @Test
    void shouldFailWhenConfiguredToolDoesNotExist() {
        Map<String, Object> data = validConfig();
        data.put("tools", List.of("calculator"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(agentNode(data), Map.of("input", "2+2")));

        assertTrue(exception.getMessage().contains("unknown tools"));
    }

    @Test
    void shouldRunPlannerWorkerReviewerCollaborationMode() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("collaborationMode", "planner_worker_reviewer");
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(
                        ReasoningResult.finalAnswer("plan thought", "1. retrieve\n2. answer"),
                        ReasoningResult.finalAnswer("worker thought", "draft answer"),
                        ReasoningResult.finalAnswer("review thought", "reviewed answer")
                );

        Map<String, Object> output = executor.execute(node, Map.of("input", "VPN issue"));

        assertEquals("reviewed answer", output.get("output"));
        assertEquals("planner_worker_reviewer", output.get("collaborationMode"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = (List<Map<String, Object>>) output.get("agentTrace");
        assertNotNull(trace);
        assertEquals(3, trace.size());
        assertEquals("planner", trace.get(0).get("stage"));
        assertEquals("worker", trace.get(1).get("stage"));
        assertEquals("reviewer", trace.get(2).get("stage"));
        verify(reasoningEngine, times(3)).reason(any(AgentState.class), anyList(), eq(chatClient));
    }

    @Test
    void shouldPreserveWorkerAnswerWhenReviewerFails() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("collaborationMode", "planner_worker_reviewer");
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(
                        ReasoningResult.finalAnswer("plan thought", "1. retrieve\n2. answer"),
                        ReasoningResult.finalAnswer("worker thought", "worker answer"),
                        ReasoningResult.error("Reviewer parse failed")
                );

        Map<String, Object> output = executor.execute(node, Map.of("input", "VPN issue"));

        assertEquals("worker answer", output.get("output"));
        assertNull(output.get("error"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = (List<Map<String, Object>>) output.get("agentTrace");
        assertNotNull(trace);
        assertEquals(3, trace.size());
        assertEquals("Reviewer parse failed", trace.get(2).get("error"));
        verify(reasoningEngine, times(3)).reason(any(AgentState.class), anyList(), eq(chatClient));
    }

    @Test
    void shouldSkipReviewerWhenReviewerDisabled() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("collaborationMode", "planner_worker_reviewer");
        data.put("reviewerEnabled", false);
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(
                        ReasoningResult.finalAnswer("plan thought", "1. retrieve\n2. answer"),
                        ReasoningResult.finalAnswer("worker thought", "worker answer")
                );

        Map<String, Object> output = executor.execute(node, Map.of("input", "VPN issue"));

        assertEquals("worker answer", output.get("output"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trace = (List<Map<String, Object>>) output.get("agentTrace");
        assertNotNull(trace);
        assertEquals(2, trace.size());
        assertEquals("planner", trace.get(0).get("stage"));
        assertEquals("worker", trace.get(1).get("stage"));
        verify(reasoningEngine, times(2)).reason(any(AgentState.class), anyList(), eq(chatClient));
    }

    @Test
    void shouldRecordSkillEvolutionCandidateWhenAgentFailsAndSkillConfigured() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("skillName", "service-desk-answer");
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.error("Failed to parse LLM response"));

        Map<String, Object> output = executor.execute(node, Map.of("input", "VPN issue"));

        assertEquals(Boolean.TRUE, output.get("skillEvolutionCandidateRecorded"));
        verify(skillEvolutionService).recordCandidate(
                eq("service-desk-answer"),
                eq("AGENT_EXECUTION"),
                isNull(),
                eq("AGENT_FAILURE"),
                contains("Failed to parse"),
                contains("VPN issue")
        );
    }

    @Test
    void shouldRecordSkillEvolutionCandidateWhenStructuredAnswerHasLowConfidence() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("skillName", "service-desk-answer");
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer(
                        "Done",
                        "{\"answer\":\"Restart the VPN profile.\",\"citations\":[{\"source\":\"vpn.md\"}],\"confidence\":0.31}"
                ));

        Map<String, Object> output = executor.execute(node, Map.of("input", "VPN issue"));

        assertEquals(Boolean.TRUE, output.get("skillEvolutionCandidateRecorded"));
        verify(skillEvolutionService).recordCandidate(
                eq("service-desk-answer"),
                eq("AGENT_EXECUTION"),
                isNull(),
                eq("LOW_CONFIDENCE"),
                contains("confidence=0.31"),
                contains("VPN issue")
        );
    }

    @Test
    void shouldRecordSkillEvolutionCandidateWhenStructuredAnswerHasNoCitations() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("skillName", "service-desk-answer");
        WorkflowNode node = agentNode(data);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer(
                        "Done",
                        "{\"answer\":\"Restart the VPN profile.\",\"citations\":[],\"confidence\":0.86}"
                ));

        Map<String, Object> output = executor.execute(node, Map.of("input", "VPN issue"));

        assertEquals(Boolean.TRUE, output.get("skillEvolutionCandidateRecorded"));
        verify(skillEvolutionService).recordCandidate(
                eq("service-desk-answer"),
                eq("AGENT_EXECUTION"),
                isNull(),
                eq("MISSING_CITATION"),
                contains("without citations"),
                contains("VPN issue")
        );
    }
}
