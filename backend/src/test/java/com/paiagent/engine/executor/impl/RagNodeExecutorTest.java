package com.paiagent.engine.executor.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.paiagent.dto.RetrievedChunk;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagNodeExecutorTest {

    @Test
    void shouldResolveQuestionFromSpecificNodeOutput() {
        RagNodeExecutor executor = new RagNodeExecutor(null);
        Map<String, Object> data = Map.of(
                "inputParams",
                List.of(questionReference("llm-1.output"))
        );

        Map<String, Object> input = new HashMap<>();
        input.put("output", "wrong flat value");
        input.put("__nodeOutputs__", Map.of(
                "llm-1", Map.of("output", "expected question"),
                "llm-2", Map.of("output", "wrong node value")
        ));

        String question = ReflectionTestUtils.invokeMethod(executor, "resolveQuestion", data, input);

        assertEquals("expected question", question);
    }

    @Test
    void shouldResolveQuestionFromNestedNodeOutput() {
        RagNodeExecutor executor = new RagNodeExecutor(null);
        Map<String, Object> data = Map.of(
                "inputParams",
                List.of(questionReference("llm-1.payload.question"))
        );

        Map<String, Object> input = Map.of(
                "__nodeOutputs__", Map.of(
                        "llm-1", Map.of("payload", Map.of("question", "nested question"))
                )
        );

        String question = ReflectionTestUtils.invokeMethod(executor, "resolveQuestion", data, input);

        assertEquals("nested question", question);
    }

    @Test
    void shouldKeepQuestionUserInputCompatibilityFallback() {
        RagNodeExecutor executor = new RagNodeExecutor(null);
        Map<String, Object> data = Map.of(
                "inputParams",
                List.of(questionReference("input-default.user_input"))
        );

        String question = ReflectionTestUtils.invokeMethod(executor, "resolveQuestion", data, Map.of("input", "raw question"));

        assertEquals("raw question", question);
    }

    @Test
    void shouldResolveQuestionSafelyWhenInputIsNull() {
        RagNodeExecutor executor = new RagNodeExecutor(null);

        String question = ReflectionTestUtils.invokeMethod(executor, "resolveQuestion", Map.of(), null);

        assertNull(question);
    }

    @Test
    void shouldPassNearThresholdRetrievalWhenKeywordEvidenceExists() {
        RagNodeExecutor executor = new RagNodeExecutor(null);
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setScore(0.42d);
        chunk.setKeywordScore(0.31d);
        chunk.setMatchedTerms(List.of("VPN", "certificate"));

        Object decision = ReflectionTestUtils.invokeMethod(executor, "evaluateRetrievalGate", List.of(chunk), 0.5d);

        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(decision, "rejected"));
    }

    @Test
    void shouldRejectLowScoreRetrievalWithoutKeywordEvidence() {
        RagNodeExecutor executor = new RagNodeExecutor(null);
        RetrievedChunk chunk = new RetrievedChunk();
        chunk.setScore(0.31d);
        chunk.setKeywordScore(0.0d);
        chunk.setMatchedTerms(List.of());

        Object decision = ReflectionTestUtils.invokeMethod(executor, "evaluateRetrievalGate", List.of(chunk), 0.5d);

        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(decision, "rejected"));
    }

    private Map<String, Object> questionReference(String reference) {
        return Map.of(
                "name", "question",
                "type", "reference",
                "referenceNode", reference
        );
    }
}
