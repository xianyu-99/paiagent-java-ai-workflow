package com.paiagent.engine.llm;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptTemplateServiceTest {

    private final PromptTemplateService service = new PromptTemplateService();

    @Test
    void shouldResolveReferenceFromSpecificNodeOutput() {
        Map<String, Object> runtimeInput = new HashMap<>();
        runtimeInput.put("output", "wrong flat value");
        runtimeInput.put("__nodeOutputs__", Map.of(
                "llm-1", Map.of("output", "expected answer"),
                "llm-2", Map.of("output", "wrong node value")
        ));

        String prompt = service.processTemplate(
                "Answer: {{answer}}",
                List.of(referenceParam("answer", "llm-1.output")),
                runtimeInput
        );

        assertEquals("Answer: expected answer", prompt);
    }

    @Test
    void shouldResolveNestedReferenceFromSpecificNodeOutput() {
        Map<String, Object> runtimeInput = new HashMap<>();
        runtimeInput.put("__nodeOutputs__", Map.of(
                "rag-1", Map.of("payload", Map.of("answer", "nested answer"))
        ));

        String prompt = service.processTemplate(
                "Answer: {{answer}}",
                List.of(referenceParam("answer", "rag-1.payload.answer")),
                runtimeInput
        );

        assertEquals("Answer: nested answer", prompt);
    }

    @Test
    void shouldKeepUserInputCompatibilityFallback() {
        Map<String, Object> runtimeInput = new HashMap<>();
        runtimeInput.put("input", "raw user text");

        String prompt = service.processTemplate(
                "Question: {{question}}",
                List.of(referenceParam("question", "input-default.user_input")),
                runtimeInput
        );

        assertEquals("Question: raw user text", prompt);
    }

    @Test
    void shouldReplaceMissingReferenceWithEmptyString() {
        String prompt = service.processTemplate(
                "Answer: {{answer}}",
                List.of(referenceParam("answer", "missing-node.output")),
                Map.of()
        );

        assertEquals("Answer: ", prompt);
    }

    @Test
    void shouldSupportStaticInputParams() {
        String prompt = service.processTemplate(
                "System: {{instruction}}",
                List.of(Map.of("name", "instruction", "type", "input", "value", "be concise")),
                Map.of()
        );

        assertEquals("System: be concise", prompt);
    }

    @Test
    void shouldReplaceTemplateVariablesWithWhitespace() {
        String prompt = service.processTemplate(
                "System: {{ instruction }}",
                List.of(Map.of("name", "instruction", "type", "input", "value", "be concise")),
                Map.of()
        );

        assertEquals("System: be concise", prompt);
    }

    private Map<String, Object> referenceParam(String name, String reference) {
        return Map.of(
                "name", name,
                "type", "reference",
                "referenceNode", reference
        );
    }
}
