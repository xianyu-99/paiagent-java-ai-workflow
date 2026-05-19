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
                    new StubExecutor("condition")
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
    void shouldTreatNullEdgesAsEmpty() {
        WorkflowConfig config = config(List.of(node("input-1", "input")), null);

        assertDoesNotThrow(() -> validator.validate(config));
    }

    private WorkflowConfig config(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(edges);
        return config;
    }

    private WorkflowNode node(String id, String type) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType(type);
        node.setData(Map.of("type", type));
        return node;
    }

    private WorkflowEdge edge(String id, String source, String target) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
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
