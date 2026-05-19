package com.paiagent.engine.executor.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private Map<String, Object> questionReference(String reference) {
        return Map.of(
                "name", "question",
                "type", "reference",
                "referenceNode", reference
        );
    }
}
