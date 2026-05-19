package com.paiagent.engine.executor.impl;

import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputNodeExecutorTest {

    private final OutputNodeExecutor executor = new OutputNodeExecutor();

    @Test
    void shouldResolveReferencedNodeOutputByNodeId() {
        WorkflowNode node = outputNode(
                "Answer: {{answer}}",
                List.of(referenceParam("answer", "llm-1.output"))
        );

        Map<String, Object> input = new HashMap<>();
        input.put("output", "wrong flat value");
        input.put("__nodeOutputs__", Map.of(
                "llm-1", Map.of("output", "expected answer"),
                "llm-2", Map.of("output", "wrong node value")
        ));

        Map<String, Object> output = executor.execute(node, input);

        assertEquals("Answer: expected answer", output.get("output"));
    }

    @Test
    void shouldResolveNestedReferencedNodeOutput() {
        WorkflowNode node = outputNode(
                "Answer: {{ answer }}",
                List.of(referenceParam("answer", "rag-1.payload.answer"))
        );

        Map<String, Object> input = Map.of(
                "__nodeOutputs__", Map.of(
                        "rag-1", Map.of("payload", Map.of("answer", "nested answer"))
                )
        );

        Map<String, Object> output = executor.execute(node, input);

        assertEquals("Answer: nested answer", output.get("output"));
    }

    @Test
    void shouldKeepUserInputCompatibilityFallback() {
        WorkflowNode node = outputNode(
                "Question: {{question}}",
                List.of(referenceParam("question", "input-default.user_input"))
        );

        Map<String, Object> output = executor.execute(node, Map.of("input", "raw user text"));

        assertEquals("Question: raw user text", output.get("output"));
    }

    private WorkflowNode outputNode(String responseContent, List<Map<String, Object>> outputParams) {
        WorkflowNode node = new WorkflowNode();
        node.setId("output-1");
        node.setType("output");
        node.setData(Map.of(
                "responseContent", responseContent,
                "outputParams", outputParams
        ));
        return node;
    }

    private Map<String, Object> referenceParam(String name, String reference) {
        return Map.of(
                "name", name,
                "type", "reference",
                "referenceNode", reference
        );
    }
}
