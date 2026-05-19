package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TTSNodeExecutorTest {

    @Test
    void shouldResolveReferencedNodeOutputByNodeId() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        WorkflowNode node = new WorkflowNode();
        node.setData(Map.of(
                "inputParams",
                List.of(Map.of(
                        "name", "text",
                        "type", "reference",
                        "referenceNode", "llm-1.output"
                ))
        ));

        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        nodeOutputs.put("llm-1", Map.of("output", "expected text"));
        nodeOutputs.put("other-llm", Map.of("output", "wrong text"));
        Map<String, Object> input = new HashMap<>();
        input.put("output", "flat fallback text");
        input.put("__nodeOutputs__", nodeOutputs);

        String resolved = ReflectionTestUtils.invokeMethod(executor, "extractInputText", node, input);

        assertEquals("expected text", resolved);
    }

    @Test
    void shouldResolveNestedReferencedNodeOutput() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        WorkflowNode node = new WorkflowNode();
        node.setData(Map.of(
                "inputParams",
                List.of(Map.of(
                        "name", "text",
                        "type", "reference",
                        "referenceNode", "rag-1.payload.answer"
                ))
        ));

        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        nodeOutputs.put("rag-1", Map.of("payload", Map.of("answer", "nested text")));
        Map<String, Object> input = new HashMap<>();
        input.put("__nodeOutputs__", nodeOutputs);

        String resolved = ReflectionTestUtils.invokeMethod(executor, "extractInputText", node, input);

        assertEquals("nested text", resolved);
    }

    @Test
    void shouldResolveUserInputCompatibilityReference() {
        TTSNodeExecutor executor = new TTSNodeExecutor();
        WorkflowNode node = new WorkflowNode();
        node.setData(Map.of(
                "inputParams",
                List.of(Map.of(
                        "name", "text",
                        "type", "reference",
                        "referenceNode", "input-default.user_input"
                ))
        ));

        Map<String, Object> input = new HashMap<>();
        input.put("input", "raw user text");

        String resolved = ReflectionTestUtils.invokeMethod(executor, "extractInputText", node, input);

        assertEquals("raw user text", resolved);
    }
}
