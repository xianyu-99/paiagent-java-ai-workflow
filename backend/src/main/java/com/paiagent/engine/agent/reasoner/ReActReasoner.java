package com.paiagent.engine.agent.reasoner;

import com.paiagent.engine.agent.AgentState;
import com.paiagent.engine.agent.ReActPromptBuilder;
import com.paiagent.engine.agent.ReasoningEngine;
import com.paiagent.engine.agent.ReasoningResult;
import com.paiagent.engine.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ReActReasoner implements ReasoningEngine {

    private static final Pattern THOUGHT_PATTERN = Pattern.compile(
            "Thought:\\s*(.+?)(?=\\n(?:Action:|Final Answer:|$))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(\\S+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTION_INPUT_PATTERN = Pattern.compile(
            "Action Input:\\s*(\\{.*?\\}|\\S+)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile(
            "Final Answer:\\s*(.+)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    @Override
    public String getMode() {
        return "react";
    }

    @Override
    public ReasoningResult reason(AgentState state, List<Tool> availableTools, ChatClient chatClient) {
        // 1. Build system prompt with available tools
        String systemPrompt = ReActPromptBuilder.buildSystemPrompt(
                availableTools, state.getSystemPrompt(), state.getMemoryContext());

        // 2. Build user prompt with task + history
        String userPrompt = ReActPromptBuilder.buildUserPrompt(state.getTask(), state.getHistoryAsText(), state.getMemoryContext());

        // 3. Call LLM via chatClient
        String rawResponse;
        try {
            ChatResponse chatResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            rawResponse = chatResponse.getResult().getOutput().getContent();
        } catch (Exception e) {
            log.error("LLM call failed during ReAct reasoning", e);
            return ReasoningResult.error("LLM call failed: " + e.getMessage());
        }

        if (rawResponse == null || rawResponse.isBlank()) {
            log.warn("LLM returned blank response");
            return ReasoningResult.error("Failed to parse LLM response");
        }

        log.debug("Raw LLM response:\n{}", rawResponse);

        // 4. Parse response to extract Thought/Action/Action Input/Final Answer
        String thought = extractThought(rawResponse);

        String finalAnswer = extractFinalAnswer(rawResponse);
        if (finalAnswer != null) {
            log.info("ReAct decision: FINAL_ANSWER, thought: {}",
                    thought != null ? thought.substring(0, Math.min(thought.length(), 100)) : "(none)");
            return ReasoningResult.finalAnswer(thought != null ? thought : "", finalAnswer);
        }

        String action = extractAction(rawResponse);
        if (action != null) {
            String actionInput = extractActionInput(rawResponse);
            if (actionInput == null) {
                actionInput = "{}";
            }
            log.info("ReAct decision: ACTION, tool: {}, thought: {}",
                    action,
                    thought != null ? thought.substring(0, Math.min(thought.length(), 100)) : "(none)");
            return ReasoningResult.action(thought != null ? thought : "", action, actionInput);
        }

        // 5. No recognizable format
        log.warn("Failed to parse ReAct response. Raw response: {}",
                rawResponse.substring(0, Math.min(rawResponse.length(), 200)));
        return ReasoningResult.error("Failed to parse LLM response");
    }

    private String extractThought(String rawResponse) {
        Matcher matcher = THOUGHT_PATTERN.matcher(rawResponse);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractAction(String rawResponse) {
        Matcher matcher = ACTION_PATTERN.matcher(rawResponse);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractActionInput(String rawResponse) {
        Matcher matcher = ACTION_INPUT_PATTERN.matcher(rawResponse);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private String extractFinalAnswer(String rawResponse) {
        Matcher matcher = FINAL_ANSWER_PATTERN.matcher(rawResponse);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
}
