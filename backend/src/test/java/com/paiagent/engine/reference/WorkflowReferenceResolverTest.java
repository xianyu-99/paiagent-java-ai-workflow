package com.paiagent.engine.reference;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkflowReferenceResolverTest {

    @Test
    void shouldNotFallbackToFlatInputWhenExplicitNodeIsMissing() {
        Map<String, Object> runtimeInput = new HashMap<>();
        runtimeInput.put("output", "wrong flat value");
        runtimeInput.put("__nodeOutputs__", Map.of(
                "llm-1", Map.of("output", "expected answer")
        ));

        Object value = WorkflowReferenceResolver.resolve("missing-node.output", runtimeInput);

        assertNull(value);
    }

    @Test
    void shouldNotFallbackToFlatInputWhenExplicitFieldIsMissing() {
        Map<String, Object> runtimeInput = new HashMap<>();
        runtimeInput.put("output", "wrong flat value");
        runtimeInput.put("__nodeOutputs__", Map.of(
                "llm-1", Map.of("tokens", 12)
        ));

        Object value = WorkflowReferenceResolver.resolve("llm-1.output", runtimeInput);

        assertNull(value);
    }

    @Test
    void shouldKeepUserInputCompatibilityFallback() {
        Map<String, Object> runtimeInput = new HashMap<>();
        runtimeInput.put("input", "raw user text");
        runtimeInput.put("__nodeOutputs__", Map.of());

        Object value = WorkflowReferenceResolver.resolve("input-default.user_input", runtimeInput);

        assertEquals("raw user text", value);
    }

    @Test
    void shouldKeepLegacyFlatFallbackWhenNodeOutputsContextIsAbsent() {
        Map<String, Object> runtimeInput = Map.of("output", "legacy value");

        Object value = WorkflowReferenceResolver.resolve("llm-1.output", runtimeInput);

        assertEquals("legacy value", value);
    }
}
