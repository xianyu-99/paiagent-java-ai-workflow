package com.paiagent.engine.validation;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowConfigValidatorTest {

    private final WorkflowConfigValidator validator = new WorkflowConfigValidator(
            new NodeExecutorFactory(List.of(
                    new StubExecutor("input"),
                    new StubExecutor("output"),
                    new StubExecutor("condition"),
                    new StubExecutor("llm"),
                    new StubExecutor("rag"),
                    new StubExecutor("media"),
                    new StubExecutor("tts")
            ))
    );

    @Test
    void shouldAcceptValidWorkflowWithLoop() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("condition-1", "condition"),
                        node("output-1", "output")
                ),
                List.of(
                        edge("e1", "input-1", "condition-1"),
                        edge("e2", "condition-1", "output-1"),
                        edge("e3", "output-1", "condition-1")
                )
        );

        assertDoesNotThrow(() -> validator.validate(config));
    }

    @Test
    void shouldRejectDuplicateNodeId() {
        WorkflowConfig config = config(
                List.of(node("input-1", "input"), node("input-1", "output")),
                List.of()
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("Duplicate node id"));
    }

    @Test
    void shouldRejectDanglingEdge() {
        WorkflowConfig config = config(
                List.of(node("input-1", "input")),
                List.of(edge("e1", "input-1", "missing-output"))
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("missing target node"));
    }

    @Test
    void shouldRejectUnsupportedNodeType() {
        WorkflowConfig config = config(
                List.of(node("tool-1", "unknown-tool")),
                List.of()
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("Unsupported node type"));
    }

    @Test
    void shouldRejectReferenceToMissingNode() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("output-1", "output", Map.of(
                                "type", "output",
                                "outputParams", List.of(Map.of(
                                        "name", "answer",
                                        "type", "reference",
                                        "referenceNode", "missing-node.output"
                                ))
                        ))
                ),
                List.of(edge("e1", "input-1", "output-1"))
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("references missing node"));
    }

    @Test
    void shouldRejectUnsupportedConditionBranchHandle() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("condition-1", "condition"),
                        node("output-1", "output")
                ),
                List.of(
                        edge("e1", "input-1", "condition-1"),
                        edge("e2", "condition-1", "output-1", "maybe")
                )
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("unsupported branch handle"));
    }

    @Test
    void shouldRejectMultipleEntryNodes() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("input-2", "input"),
                        node("output-1", "output")
                ),
                List.of(edge("e1", "input-1", "output-1"))
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("exactly one entry node"));
    }

    @Test
    void shouldTreatNullEdgesAsEmpty() {
        WorkflowConfig config = config(List.of(node("input-1", "input")), null);

        assertDoesNotThrow(() -> validator.validate(config));
    }

    @Test
    void shouldRejectLlmNodeWithoutModelConfiguration() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("llm-1", "llm", Map.of("type", "llm", "prompt", "hello")),
                        node("output-1", "output")
                ),
                List.of(
                        edge("e1", "input-1", "llm-1"),
                        edge("e2", "llm-1", "output-1")
                )
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("missing llm provider"));
    }

    @Test
    void shouldRejectMediaNodeWithoutApiKey() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("media-1", "media", Map.of(
                                "type", "media",
                                "apiUrl", "https://api.example.com/v1/images",
                                "model", "image-model"
                        )),
                        node("output-1", "output")
                ),
                List.of(
                        edge("e1", "input-1", "media-1"),
                        edge("e2", "media-1", "output-1")
                )
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("missing media API key"));
    }

    @Test
    void shouldRejectRagNodeWithoutKnowledgeBase() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("rag-1", "rag", Map.of(
                                "type", "rag",
                                "configId", 7L,
                                "inputParams", List.of(Map.of(
                                        "name", "question",
                                        "type", "reference",
                                        "referenceNode", "input-1.input"
                                ))
                        )),
                        node("output-1", "output")
                ),
                List.of(
                        edge("e1", "input-1", "rag-1"),
                        edge("e2", "rag-1", "output-1")
                )
        );

        WorkflowValidationException error = assertThrows(
                WorkflowValidationException.class,
                () -> validator.validate(config)
        );

        assertTrue(error.getMessage().contains("missing knowledgeBaseId"));
    }

    @Test
    void shouldAcceptRuntimeConfiguredNodes() {
        WorkflowConfig config = config(
                List.of(
                        node("input-1", "input"),
                        node("llm-1", "llm", Map.of(
                                "type", "llm",
                                "prompt", "hello",
                                "provider", "openai",
                                "apiUrl", "https://api.example.com",
                                "apiKey", "sk-test",
                                "model", "gpt-test"
                        )),
                        node("tts-1", "tts", Map.of(
                                "type", "tts",
                                "apiKeyConfigured", true,
                                "model", "tts-test"
                        )),
                        node("output-1", "output")
                ),
                List.of(
                        edge("e1", "input-1", "llm-1"),
                        edge("e2", "llm-1", "tts-1"),
                        edge("e3", "tts-1", "output-1")
                )
        );

        assertDoesNotThrow(() -> validator.validate(config));
    }

    private WorkflowConfig config(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);
        return config;
    }

    private WorkflowNode node(String id, String type) {
        return node(id, type, Map.of("type", type));
    }

    private WorkflowNode node(String id, String type, Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType(type);
        node.setData(data);
        return node;
    }

    private WorkflowEdge edge(String id, String source, String target) {
        return edge(id, source, target, null);
    }

    private WorkflowEdge edge(String id, String source, String target, String sourceHandle) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }

    private record StubExecutor(String supportedNodeType) implements NodeExecutor {

        @Override
        public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
            return input;
        }

        @Override
        public Map<String, Object> execute(
                WorkflowNode node,
                Map<String, Object> input,
                Consumer<ExecutionEvent> progressCallback
        ) {
            return input;
        }

        @Override
        public String getSupportedNodeType() {
            return supportedNodeType;
        }
    }
}
