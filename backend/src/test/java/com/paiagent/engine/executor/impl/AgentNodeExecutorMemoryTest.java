package com.paiagent.engine.executor.impl;

import com.paiagent.engine.agent.AgentState;
import com.paiagent.engine.agent.ReasoningEngine;
import com.paiagent.engine.agent.ReasoningResult;
import com.paiagent.engine.agent.memory.AgentMemoryService;
import com.paiagent.engine.agent.tool.ToolRegistry;
import com.paiagent.engine.llm.ChatClientFactory;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.LLMGlobalConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentNodeExecutorMemoryTest {

    @Mock
    ToolRegistry toolRegistry;

    @Mock
    ReasoningEngine reasoningEngine;

    @Mock
    ChatClientFactory chatClientFactory;

    @Mock
    LLMGlobalConfigService llmGlobalConfigService;

    @Mock
    AgentMemoryService agentMemoryService;

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
    void shouldBuildAndSetMemoryContext_whenKnowledgeBaseIdConfigured() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("knowledgeBaseId", 42L);
        WorkflowNode node = agentNode(data);

        Map<String, Object> input = new HashMap<>();
        input.put("input", "What is AI?");
        input.put("__executionFlowId__", 100L);

        String memoryContext = "=== Knowledge Context ===\n[Knowledge 1] (score: 0.95)\nAI stands for Artificial Intelligence.";

        when(agentMemoryService.buildMemoryContext(
                eq("What is AI?"), eq(42L), eq(100L),
                eq(false), eq(3), eq(0.5)))
                .thenReturn(memoryContext);
        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        Map<String, Object> output = executor.execute(node, input);

        assertEquals("Answer", output.get("output"));
        verify(agentMemoryService).buildMemoryContext(
                eq("What is AI?"), eq(42L), eq(100L), eq(false), eq(3), eq(0.5));
    }

    @Test
    void shouldBuildAndSetMemoryContext_whenEnableExecutionMemoryIsTrue() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("enableExecutionMemory", true);
        data.put("memoryTopK", 5);
        data.put("memoryMinScore", 0.7);
        WorkflowNode node = agentNode(data);

        Map<String, Object> input = new HashMap<>();
        input.put("input", "Summarize previous runs");
        input.put("__executionFlowId__", 200L);

        String memoryContext = "=== Execution History ===\n[Past Execution 1] (similarity: 0.92)\nInput: hello\nOutput: hi";

        when(agentMemoryService.buildMemoryContext(
                eq("Summarize previous runs"), isNull(), eq(200L),
                eq(true), eq(5), eq(0.7)))
                .thenReturn(memoryContext);
        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        Map<String, Object> output = executor.execute(node, input);

        assertEquals("Answer", output.get("output"));
        verify(agentMemoryService).buildMemoryContext(
                eq("Summarize previous runs"), isNull(), eq(200L), eq(true), eq(5), eq(0.7));
    }

    @Test
    void shouldExtractFlowIdCorrectly_fromInputMap() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("knowledgeBaseId", 1L);
        WorkflowNode node = agentNode(data);

        Map<String, Object> input = new HashMap<>();
        input.put("input", "Test");
        input.put("__executionFlowId__", 999L);

        when(agentMemoryService.buildMemoryContext(
                anyString(), any(), eq(999L), anyBoolean(), anyInt(), anyDouble()))
                .thenReturn(null);
        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        executor.execute(node, input);

        verify(agentMemoryService).buildMemoryContext(
                eq("Test"), eq(1L), eq(999L), eq(false), eq(3), eq(0.5));
    }

    @Test
    void shouldNotRetrieveMemory_whenBothKnowledgeBaseIdAndExecutionMemoryAbsent() throws Exception {
        Map<String, Object> data = validConfig();
        // No knowledgeBaseId, no enableExecutionMemory
        WorkflowNode node = agentNode(data);

        Map<String, Object> input = new HashMap<>();
        input.put("input", "Simple task");
        input.put("__executionFlowId__", 300L);

        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        Map<String, Object> output = executor.execute(node, input);

        assertEquals("Answer", output.get("output"));
        verifyNoInteractions(agentMemoryService);
    }

    @Test
    void shouldHandleAgentMemoryServiceExceptionGracefully() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("knowledgeBaseId", 7L);
        data.put("enableExecutionMemory", true);
        WorkflowNode node = agentNode(data);

        Map<String, Object> input = new HashMap<>();
        input.put("input", "Risky task");
        input.put("__executionFlowId__", 400L);

        when(agentMemoryService.buildMemoryContext(
                anyString(), any(), any(), anyBoolean(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("Embedding service unavailable"));
        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        Map<String, Object> output = executor.execute(node, input);

        assertEquals("Answer", output.get("output"));
        verify(agentMemoryService).buildMemoryContext(
                eq("Risky task"), eq(7L), eq(400L), eq(true), eq(3), eq(0.5));
    }

    @Test
    void shouldIncludeMemoryContextInOutput() throws Exception {
        Map<String, Object> data = validConfig();
        data.put("knowledgeBaseId", 99L);
        WorkflowNode node = agentNode(data);

        Map<String, Object> input = new HashMap<>();
        input.put("input", "What is AI?");
        input.put("__executionFlowId__", 500L);

        String testMemoryContext = "Test memory context";

        when(agentMemoryService.buildMemoryContext(
                eq("What is AI?"), eq(99L), eq(500L),
                eq(false), eq(3), eq(0.5)))
                .thenReturn(testMemoryContext);
        when(toolRegistry.getAllTools()).thenReturn(Collections.emptyList());
        when(chatClientFactory.createClient(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(chatClient);
        when(reasoningEngine.getMode()).thenReturn("react");
        when(reasoningEngine.reason(any(AgentState.class), anyList(), eq(chatClient)))
                .thenReturn(ReasoningResult.finalAnswer("Done", "Answer"));

        Map<String, Object> output = executor.execute(node, input);

        assertEquals("Answer", output.get("output"));
        assertTrue(output.containsKey("memoryContext"), "Output should contain memoryContext key");
        assertEquals(testMemoryContext, output.get("memoryContext"), "memoryContext should match the value from AgentMemoryService");
    }
}
