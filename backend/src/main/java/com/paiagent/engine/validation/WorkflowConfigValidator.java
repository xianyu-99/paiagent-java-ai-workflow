package com.paiagent.engine.validation;

import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class WorkflowConfigValidator {

    private final NodeExecutorFactory executorFactory;

    public WorkflowConfigValidator(NodeExecutorFactory executorFactory) {
        this.executorFactory = executorFactory;
    }

    public void validate(WorkflowConfig config) {
        if (config == null) {
            throw invalid("Workflow config is empty");
        }

        List<WorkflowNode> nodes = config.getNodes() == null ? List.of() : config.getNodes();
        if (nodes.isEmpty()) {
            throw invalid("Workflow must contain at least one node");
        }

        List<WorkflowEdge> edges = config.getEdges() == null ? List.of() : config.getEdges();
        if (config.getEdges() == null) {
            config.setEdges(edges);
        }

        Set<String> nodeIds = validateNodes(nodes);
        validateEdges(edges, nodeIds);
    }

    private Set<String> validateNodes(List<WorkflowNode> nodes) {
        Set<String> nodeIds = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            WorkflowNode node = nodes.get(index);
            if (node == null) {
                throw invalid("Node at index " + index + " is null");
            }

            String nodeId = node.getId();
            if (isBlank(nodeId)) {
                throw invalid("Node at index " + index + " is missing id");
            }
            if (!nodeIds.add(nodeId)) {
                throw invalid("Duplicate node id: " + nodeId);
            }

            String nodeType = node.getType();
            if (isBlank(nodeType)) {
                throw invalid("Node " + nodeId + " is missing type");
            }
            if (!executorFactory.supports(nodeType)) {
                throw invalid("Unsupported node type '" + nodeType + "' on node " + nodeId
                        + ". Supported types: " + executorFactory.getSupportedNodeTypes());
            }
        }
        return nodeIds;
    }

    private void validateEdges(List<WorkflowEdge> edges, Set<String> nodeIds) {
        Set<String> edgeIds = new HashSet<>();
        for (int index = 0; index < edges.size(); index++) {
            WorkflowEdge edge = edges.get(index);
            if (edge == null) {
                throw invalid("Edge at index " + index + " is null");
            }

            String edgeLabel = isBlank(edge.getId()) ? "at index " + index : edge.getId();
            if (!isBlank(edge.getId()) && !edgeIds.add(edge.getId())) {
                throw invalid("Duplicate edge id: " + edge.getId());
            }
            if (isBlank(edge.getSource())) {
                throw invalid("Edge " + edgeLabel + " is missing source");
            }
            if (isBlank(edge.getTarget())) {
                throw invalid("Edge " + edgeLabel + " is missing target");
            }
            if (!nodeIds.contains(edge.getSource())) {
                throw invalid("Edge " + edgeLabel + " references missing source node: " + edge.getSource());
            }
            if (!nodeIds.contains(edge.getTarget())) {
                throw invalid("Edge " + edgeLabel + " references missing target node: " + edge.getTarget());
            }
        }
    }

    private WorkflowValidationException invalid(String message) {
        return new WorkflowValidationException(message);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
