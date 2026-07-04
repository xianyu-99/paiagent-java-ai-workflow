package com.paiagent.engine.validation;

import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Component
public class WorkflowConfigValidator {

    private static final Set<String> LLM_NODE_TYPES = Set.of(
            "llm",
            "openai",
            "deepseek",
            "qwen",
            "step",
            "zhipu",
            "ai_ping",
            "hyde",
            "query_expansion"
    );

    private static final Set<String> PROMPT_REQUIRED_LLM_NODE_TYPES = Set.of(
            "llm",
            "openai",
            "deepseek",
            "qwen",
            "step",
            "zhipu",
            "ai_ping"
    );

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

        Map<String, String> nodeTypes = validateNodes(nodes);
        Set<String> nodeIds = nodeTypes.keySet();
        validateReferences(nodes, nodeIds);
        validateEdges(edges, nodeIds, nodeTypes);
        validateEntryAndReachability(nodes, edges);
    }

    private Map<String, String> validateNodes(List<WorkflowNode> nodes) {
        Set<String> nodeIds = new HashSet<>();
        Map<String, String> nodeTypes = new HashMap<>();
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
            nodeTypes.put(nodeId, nodeType);
            validateNodeConfiguration(node, nodeType);
        }
        return nodeTypes;
    }

    private void validateNodeConfiguration(WorkflowNode node, String nodeType) {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        if (LLM_NODE_TYPES.contains(nodeType)) {
            validateLlmNodeConfiguration(node, nodeType, data);
            return;
        }
        switch (nodeType) {
            case "agent" -> validateAgentNodeConfiguration(node, data);
            case "tts" -> validateTtsNodeConfiguration(node, data);
            case "media" -> validateMediaNodeConfiguration(node, data);
            case "rag" -> validateRagNodeConfiguration(node, data);
            default -> {
            }
        }
    }

    private void validateLlmNodeConfiguration(WorkflowNode node, String nodeType, Map<String, Object> data) {
        if (PROMPT_REQUIRED_LLM_NODE_TYPES.contains(nodeType) && isBlank(stringValue(data.get("prompt")))) {
            throw invalid("Node " + node.getId() + " is missing prompt");
        }
        validateModelConfiguration(node, nodeType, data, "llm".equals(nodeType));
    }

    private void validateAgentNodeConfiguration(WorkflowNode node, Map<String, Object> data) {
        if (isBlank(stringValue(data.get("systemPrompt"))) && isBlank(stringValue(data.get("prompt")))) {
            throw invalid("Node " + node.getId() + " is missing systemPrompt");
        }
        validateModelConfiguration(node, "agent", data, true);
    }

    private void validateTtsNodeConfiguration(WorkflowNode node, Map<String, Object> data) {
        if (isBlank(stringValue(data.get("model")))) {
            throw invalid("Node " + node.getId() + " is missing TTS model");
        }
        if (isBlank(stringValue(data.get("apiKey"))) && !Boolean.TRUE.equals(data.get("apiKeyConfigured"))) {
            throw invalid("Node " + node.getId() + " is missing TTS API key");
        }
    }

    private void validateMediaNodeConfiguration(WorkflowNode node, Map<String, Object> data) {
        if (isBlank(stringValue(data.get("apiUrl")))) {
            throw invalid("Node " + node.getId() + " is missing media API URL");
        }
        if (isBlank(stringValue(data.get("apiKey")))) {
            throw invalid("Node " + node.getId() + " is missing media API key");
        }
        if (isBlank(stringValue(data.get("model")))) {
            throw invalid("Node " + node.getId() + " is missing media model");
        }
    }

    private void validateRagNodeConfiguration(WorkflowNode node, Map<String, Object> data) {
        if (data.get("knowledgeBaseId") == null) {
            throw invalid("Node " + node.getId() + " is missing knowledgeBaseId");
        }
        if (!Boolean.TRUE.equals(data.get("retrievalOnly"))) {
            validateModelConfiguration(node, "rag", data, false);
        }
    }

    private void validateModelConfiguration(
            WorkflowNode node,
            String nodeType,
            Map<String, Object> data,
            boolean requireProvider
    ) {
        if (hasConfigId(data)) {
            return;
        }
        if (requireProvider && isBlank(stringValue(data.get("provider")))) {
            throw invalid("Node " + node.getId() + " is missing " + nodeType + " provider");
        }
        if (isBlank(stringValue(data.get("apiUrl")))) {
            throw invalid("Node " + node.getId() + " is missing " + nodeType + " API URL");
        }
        if (isBlank(stringValue(data.get("apiKey")))) {
            throw invalid("Node " + node.getId() + " is missing " + nodeType + " API key");
        }
        if (isBlank(stringValue(data.get("model")))) {
            throw invalid("Node " + node.getId() + " is missing " + nodeType + " model");
        }
    }

    private void validateEdges(List<WorkflowEdge> edges, Set<String> nodeIds, Map<String, String> nodeTypes) {
        Set<String> edgeIds = new HashSet<>();
        Map<String, List<WorkflowEdge>> outgoingEdges = new HashMap<>();
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
            outgoingEdges.computeIfAbsent(edge.getSource(), key -> new ArrayList<>()).add(edge);
        }

        for (Map.Entry<String, List<WorkflowEdge>> entry : outgoingEdges.entrySet()) {
            if ("condition".equals(nodeTypes.get(entry.getKey()))) {
                validateConditionOutgoingEdges(entry.getKey(), entry.getValue());
            }
        }
    }

    private void validateReferences(List<WorkflowNode> nodes, Set<String> nodeIds) {
        for (WorkflowNode node : nodes) {
            Map<String, Object> data = node.getData();
            if (data == null) {
                continue;
            }

            validateParamReferences(node, "inputParams", data.get("inputParams"), nodeIds);
            validateParamReferences(node, "outputParams", data.get("outputParams"), nodeIds);

            if ("condition".equals(node.getType())) {
                String leftType = stringValue(data.getOrDefault("leftType", "reference"));
                String leftReference = stringValue(data.get("leftReference"));
                if ("reference".equals(leftType) && !isBlank(leftReference)) {
                    validateReference(node.getId(), "leftReference", leftReference, nodeIds);
                }
            }
        }
    }

    private void validateParamReferences(
            WorkflowNode node,
            String fieldName,
            Object paramsObject,
            Set<String> nodeIds
    ) {
        if (paramsObject == null) {
            return;
        }
        if (!(paramsObject instanceof List<?> params)) {
            throw invalid("Node " + node.getId() + " field " + fieldName + " must be a list");
        }

        for (int index = 0; index < params.size(); index++) {
            Object item = params.get(index);
            if (!(item instanceof Map<?, ?> param)) {
                throw invalid("Node " + node.getId() + " field " + fieldName + "[" + index + "] must be an object");
            }
            if (!"reference".equals(stringValue(param.get("type")))) {
                continue;
            }

            String paramName = stringValue(param.get("name"));
            String label = fieldName + "[" + index + "]" + (isBlank(paramName) ? "" : " '" + paramName + "'");
            validateReference(node.getId(), label, stringValue(param.get("referenceNode")), nodeIds);
        }
    }

    private void validateReference(String nodeId, String fieldName, String reference, Set<String> nodeIds) {
        if (isBlank(reference)) {
            throw invalid("Node " + nodeId + " has empty reference in " + fieldName);
        }
        if (!reference.contains(".")) {
            return;
        }

        String[] parts = reference.split("\\.", 2);
        if (isBlank(parts[0]) || parts.length < 2 || isBlank(parts[1])) {
            throw invalid("Node " + nodeId + " has invalid reference in " + fieldName + ": " + reference);
        }
        if (!nodeIds.contains(parts[0])) {
            throw invalid("Node " + nodeId + " references missing node in " + fieldName + ": " + reference);
        }
    }

    private void validateConditionOutgoingEdges(String nodeId, List<WorkflowEdge> outgoing) {
        boolean hasExplicitHandle = outgoing.stream().anyMatch(edge -> !isBlank(edge.getSourceHandle()));
        if (!hasExplicitHandle) {
            if (outgoing.size() > 2) {
                throw invalid("Condition node " + nodeId + " has more than two implicit branches");
            }
            return;
        }

        Set<String> branches = new HashSet<>();
        for (WorkflowEdge edge : outgoing) {
            if (isBlank(edge.getSourceHandle())) {
                throw invalid("Condition node " + nodeId + " mixes explicit and implicit branch handles");
            }
            String branch = normalizeBranch(edge.getSourceHandle());
            if (branch == null) {
                throw invalid("Condition node " + nodeId + " has unsupported branch handle: " + edge.getSourceHandle());
            }
            if (!branches.add(branch)) {
                throw invalid("Condition node " + nodeId + " has duplicate branch handle: " + branch);
            }
        }
    }

    private void validateEntryAndReachability(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        Set<String> targets = new HashSet<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (WorkflowEdge edge : edges) {
            targets.add(edge.getTarget());
            outgoing.computeIfAbsent(edge.getSource(), key -> new ArrayList<>()).add(edge.getTarget());
        }

        List<String> entries = nodes.stream()
                .map(WorkflowNode::getId)
                .filter(nodeId -> !targets.contains(nodeId))
                .toList();
        if (entries.isEmpty()) {
            throw invalid("Workflow must contain one entry node");
        }
        if (entries.size() > 1) {
            throw invalid("Workflow must contain exactly one entry node, found: " + entries);
        }

        Set<String> reachable = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.offer(entries.getFirst());
        while (!queue.isEmpty()) {
            String nodeId = queue.poll();
            if (!reachable.add(nodeId)) {
                continue;
            }
            for (String target : outgoing.getOrDefault(nodeId, List.of())) {
                queue.offer(target);
            }
        }

        List<String> unreachable = nodes.stream()
                .map(WorkflowNode::getId)
                .filter(nodeId -> !reachable.contains(nodeId))
                .toList();
        if (!unreachable.isEmpty()) {
            throw invalid("Workflow contains unreachable nodes: " + unreachable);
        }
    }

    private WorkflowValidationException invalid(String message) {
        return new WorkflowValidationException(message);
    }

    private String normalizeBranch(String sourceHandle) {
        if (isBlank(sourceHandle)) {
            return null;
        }
        String normalized = sourceHandle.toLowerCase();
        if (normalized.equals("true") || normalized.contains("true") || normalized.equals("yes")) {
            return "true";
        }
        if (normalized.equals("false") || normalized.contains("false") || normalized.equals("no") || normalized.equals("else")) {
            return "false";
        }
        return null;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean hasConfigId(Map<String, Object> data) {
        return !isBlank(stringValue(data.get("configId")));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
