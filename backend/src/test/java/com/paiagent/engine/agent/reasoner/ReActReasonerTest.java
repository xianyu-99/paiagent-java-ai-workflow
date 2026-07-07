package com.paiagent.engine.agent.reasoner;

import com.paiagent.config.PromptCacheProperties;
import com.paiagent.engine.agent.AgentState;
import com.paiagent.engine.agent.ReasoningResult;
import com.paiagent.engine.agent.tool.Tool;
import com.paiagent.engine.llm.prompt.PromptCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReActReasonerTest {

    private ReActReasoner reasoner;
    private AgentState state;
    private List<Tool> tools;
    private ChatClient chatClient;
    private ChatClient.ChatClientRequestSpec requestSpec;
    private ChatClient.CallResponseSpec responseSpec;
    private ChatResponse chatResponse;
    private Generation generation;
    private AssistantMessage assistantMessage;

    @BeforeEach
    void setUp() {
        reasoner = new ReActReasoner();
        state = new AgentState("session-1", "What is 2+2?", 5);
        tools = Collections.emptyList();

        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        responseSpec = mock(ChatClient.CallResponseSpec.class);
        chatResponse = mock(ChatResponse.class);
        generation = mock(Generation.class);
        assistantMessage = mock(AssistantMessage.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.chatResponse()).thenReturn(chatResponse);
        when(chatResponse.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(assistantMessage);
    }

    @Test
    void shouldParseFinalAnswer() {
        when(assistantMessage.getContent()).thenReturn(
                "Thought: I know the answer.\nFinal Answer: The answer is 4.");

        ReasoningResult result = reasoner.reason(state, tools, chatClient);

        assertEquals(ReasoningResult.DecisionType.FINAL_ANSWER, result.getType());
        assertEquals("I know the answer.", result.getThought());
        assertEquals("The answer is 4.", result.getFinalAnswer());
    }

    @Test
    void shouldParseAction() {
        when(assistantMessage.getContent()).thenReturn(
                "Thought: I need to use a calculator.\nAction: calculator\nAction Input: {\"expr\": \"2+2\"}");

        ReasoningResult result = reasoner.reason(state, tools, chatClient);

        assertEquals(ReasoningResult.DecisionType.ACTION, result.getType());
        assertEquals("I need to use a calculator.", result.getThought());
        assertEquals("calculator", result.getAction());
        assertEquals("{\"expr\": \"2+2\"}", result.getActionInput());
    }

    @Test
    void shouldHandleMissingActionInput() {
        when(assistantMessage.getContent()).thenReturn(
                "Thought: I need to check something.\nAction: search");

        ReasoningResult result = reasoner.reason(state, tools, chatClient);

        assertEquals(ReasoningResult.DecisionType.ACTION, result.getType());
        assertEquals("search", result.getAction());
        assertEquals("{}", result.getActionInput());
    }

    @Test
    void shouldReturnErrorOnBlankResponse() {
        when(assistantMessage.getContent()).thenReturn("   ");

        ReasoningResult result = reasoner.reason(state, tools, chatClient);

        assertEquals(ReasoningResult.DecisionType.ERROR, result.getType());
        assertEquals("Failed to parse LLM response", result.getErrorMessage());
    }

    @Test
    void shouldReturnErrorOnUnparseableResponse() {
        when(assistantMessage.getContent()).thenReturn(
                "This is just some random text without proper format.");

        ReasoningResult result = reasoner.reason(state, tools, chatClient);

        assertEquals(ReasoningResult.DecisionType.ERROR, result.getType());
        assertEquals("Failed to parse LLM response", result.getErrorMessage());
    }

    @Test
    void shouldReturnErrorOnLlmException() {
        when(requestSpec.call()).thenThrow(new RuntimeException("Connection timeout"));

        ReasoningResult result = reasoner.reason(state, tools, chatClient);

        assertEquals(ReasoningResult.DecisionType.ERROR, result.getType());
        assertTrue(result.getErrorMessage().contains("LLM call failed"));
        assertTrue(result.getErrorMessage().contains("Connection timeout"));
    }

    @Test
    void shouldReturnReactMode() {
        assertEquals("react", reasoner.getMode());
    }

    @Test
    void shouldIncludeStateSystemPromptInLlmSystemPrompt() {
        state.setSystemPrompt("Always answer in JSON.");
        when(assistantMessage.getContent()).thenReturn(
                "Thought: I can answer.\nFinal Answer: {\"answer\":\"4\"}");

        reasoner.reason(state, tools, chatClient);

        verify(requestSpec).system(contains("Always answer in JSON."));
    }

    @Test
    void shouldRecordPromptCacheHitForRepeatedStableSystemPrompt() {
        PromptCacheProperties properties = new PromptCacheProperties();
        properties.setMinimumChars(1);
        properties.setTtlSeconds(60);
        properties.setMaxEntries(10);
        reasoner = new ReActReasoner(new PromptCacheService(properties));
        state.setSystemPrompt("Always answer in JSON.");
        when(assistantMessage.getContent()).thenReturn(
                "Thought: I can answer.\nFinal Answer: {\"answer\":\"4\"}");

        reasoner.reason(state, tools, chatClient);
        reasoner.reason(state, tools, chatClient);

        assertEquals(1, state.getPromptCacheHits());
        assertEquals(1, state.getPromptCacheMisses());
        assertTrue(state.getPromptCacheEstimatedSavedChars() > 0);
    }
}
