package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.dag.DAGParser;
import com.paiagent.engine.execution.NodeExecutionAttempt;
import com.paiagent.engine.execution.NodeExecutionException;
import com.paiagent.engine.execution.NodeExecutionOutcome;
import com.paiagent.engine.execution.NodeExecutionRunner;
import com.paiagent.engine.execution.WorkflowExecutionContextHolder;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.validation.WorkflowConfigValidator;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

@Slf4j
@Service
public class WorkflowEngine implements WorkflowExecutor {

    private static final String NODE_OUTPUTS_CONTEXT_KEY = "__nodeOutputs__";
    private static final String EXECUTION_USER_ID_CONTEXT_KEY = "__executionUserId__";
    private static final String EXECUTION_ADMIN_CONTEXT_KEY = "__executionAdmin__";
    private static final String EXECUTION_FLOW_ID_CONTEXT_KEY = "__executionFlowId__";
    
    @Autowired
    private DAGParser dagParser;
    
    @Autowired
    private NodeExecutionRunner nodeExecutionRunner;

    @Autowired
    private WorkflowConfigValidator workflowConfigValidator;
    
    @Autowired
    private ExecutionRecordMapper executionRecordMapper;
    
    @Override
    public ExecutionResponse execute(Workflow workflow, String inputData) {
        return executeWithCallback(workflow, inputData, null);
    }
    
    @Override
    public ExecutionResponse executeWithCallback(Workflow workflow, String inputData, Consumer<ExecutionEvent> eventCallback) {
        long startTime = System.currentTimeMillis();

        List<ExecutionResponse.NodeResult> nodeResults = new ArrayList<>();
        Map<String, Map<String, Object>> nodeOutputs = new HashMap<>();
        List<Map<String, Object>> errorLogs = new ArrayList<>();
        
        Map<String, Object> currentInput = new HashMap<>();
        currentInput.put("input", inputData);
        
        String status = "SUCCESS";
        String errorMessage = null;
        Object outputData = null;
        int totalRetryCount = 0;
        int totalTimeoutCount = 0;
        
        ExecutionRecord record = createRunningRecord(workflow, inputData);
        executionRecordMapper.insert(record);
        
        try {
            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
            }

            WorkflowConfig config = JSON.parseObject(workflow.getFlowData(), WorkflowConfig.class);
            workflowConfigValidator.validate(config);
            List<WorkflowNode> sortedNodes = dagParser.parse(config);
            Map<String, WorkflowNode> nodeMap = buildNodeMap(sortedNodes);
            Map<String, List<com.paiagent.engine.model.WorkflowEdge>> incomingEdges = buildIncomingEdges(config);
            Map<String, List<com.paiagent.engine.model.WorkflowEdge>> outgoingEdges = buildOutgoingEdges(config);

            Queue<String> executionQueue = new LinkedList<>();
            Set<String> activeNodes = new HashSet<>();
            Set<String> scheduledNodes = new HashSet<>();
            Set<String> completedNodes = new HashSet<>();
            Set<String> skippedNodes = new HashSet<>();

            for (WorkflowNode node : sortedNodes) {
                if (incomingEdges.getOrDefault(node.getId(), List.of()).isEmpty()) {
                    activeNodes.add(node.getId());
                    scheduledNodes.add(node.getId());
                    executionQueue.offer(node.getId());
                }
            }

            if (executionQueue.isEmpty() && !sortedNodes.isEmpty()) {
                throw new IllegalStateException("工作流缺少起始节点");
            }

            while (!executionQueue.isEmpty()) {
                String nodeId = executionQueue.poll();
                WorkflowNode node = nodeMap.get(nodeId);
                if (node == null || completedNodes.contains(nodeId) || skippedNodes.contains(nodeId)) {
                    continue;
                }

                long nodeStartTime = System.currentTimeMillis();
                
                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeStart(node.getId(), node.getType()));
                }
                
                Map<String, Object> nodeInput = buildNodeInput(
                        node,
                        workflow,
                        inputData,
                        incomingEdges,
                        nodeOutputs,
                        activeNodes,
                        completedNodes,
                        skippedNodes
                );
                ExecutionResponse.NodeResult nodeResult = new ExecutionResponse.NodeResult();
                nodeResult.setNodeId(node.getId());
                nodeResult.setNodeName(node.getType());
                nodeResult.setInput(JSON.toJSONString(removeInternalContext(nodeInput)));
                
                try {
                    NodeExecutionOutcome outcome = nodeExecutionRunner.execute(node, nodeInput, eventCallback);
                    Map<String, Object> output = removeInternalContext(outcome.getOutput());

                    totalRetryCount += outcome.getRetryCount();
                    totalTimeoutCount += countTimeouts(outcome.getAttempts());
                    appendFailedAttempts(errorLogs, node, outcome.getAttempts());
                    
                    nodeOutputs.put(node.getId(), output);
                    
                    nodeResult.setStatus("SUCCESS");
                    nodeResult.setOutput(JSON.toJSONString(output));
                    nodeResult.setAttempts(outcome.getAttempts().size());
                    nodeResult.setRetryCount(outcome.getRetryCount());
                    nodeResult.setTimeoutMs(outcome.getTimeoutMs());
                    nodeResult.setAttemptLogs(outcome.getAttempts());
                    
                    long nodeEndTime = System.currentTimeMillis();
                    int nodeDuration = (int) (nodeEndTime - nodeStartTime);
                    nodeResult.setDuration(nodeDuration);
                    
                    if (eventCallback != null) {
                        Map<String, Object> eventData = new HashMap<>();
                        eventData.put("input", removeInternalContext(nodeInput));
                        eventData.put("output", output);
                        eventData.put("duration", nodeDuration);
                        eventData.put("attempts", outcome.getAttempts().size());
                        eventData.put("retryCount", outcome.getRetryCount());
                        eventData.put("timeoutMs", outcome.getTimeoutMs());
                        eventCallback.accept(ExecutionEvent.nodeSuccess(node.getId(), node.getType(), eventData, nodeDuration));
                    }
                    
                    currentInput = output;
                    completedNodes.add(nodeId);

                    List<com.paiagent.engine.model.WorkflowEdge> outgoing = outgoingEdges.getOrDefault(nodeId, List.of());
                    if ("condition".equals(node.getType())) {
                        List<com.paiagent.engine.model.WorkflowEdge> selectedEdges = selectConditionEdges(outgoing, resolveSelectedBranch(output));
                        for (com.paiagent.engine.model.WorkflowEdge edge : selectedEdges) {
                            activateTarget(edge.getTarget(), activeNodes, scheduledNodes, completedNodes, skippedNodes, incomingEdges, executionQueue);
                        }
                        for (com.paiagent.engine.model.WorkflowEdge edge : outgoing) {
                            if (!selectedEdges.contains(edge)) {
                                skipInactiveBranch(edge.getTarget(), activeNodes, completedNodes, skippedNodes, incomingEdges, outgoingEdges);
                            }
                        }
                    } else {
                        for (com.paiagent.engine.model.WorkflowEdge edge : outgoing) {
                            activateTarget(edge.getTarget(), activeNodes, scheduledNodes, completedNodes, skippedNodes, incomingEdges, executionQueue);
                        }
                    }
                    
                } catch (NodeExecutionException e) {
                    log.error("节点执行失败: {}", node.getId(), e);

                    totalRetryCount += e.getRetryCount();
                    totalTimeoutCount += countTimeouts(e.getAttempts());
                    appendFailedAttempts(errorLogs, node, e.getAttempts());

                    nodeResult.setStatus("FAILED");
                    nodeResult.setError(e.getMessage());
                    nodeResult.setErrorType(e.getErrorType());
                    nodeResult.setAttempts(e.getAttempts().size());
                    nodeResult.setRetryCount(e.getRetryCount());
                    nodeResult.setTimeoutMs(e.getTimeoutMs());
                    nodeResult.setAttemptLogs(e.getAttempts());
                    status = "FAILED";
                    errorMessage = e.getMessage();

                    if (eventCallback != null) {
                        eventCallback.accept(ExecutionEvent.nodeError(node.getId(), node.getType(), e.getMessage()));
                    }

                    throw e;
                } catch (Exception e) {
                    log.error("节点执行失败: {}", node.getId(), e);
                    nodeResult.setStatus("FAILED");
                    nodeResult.setError(e.getMessage());
                    status = "FAILED";
                    errorMessage = "节点 " + node.getId() + " 执行失败: " + e.getMessage();
                    
                    if (eventCallback != null) {
                        eventCallback.accept(ExecutionEvent.nodeError(node.getId(), node.getType(), e.getMessage()));
                    }
                    
                    throw e;
                } finally {
                    long nodeEndTime = System.currentTimeMillis();
                    nodeResult.setDuration((int) (nodeEndTime - nodeStartTime));
                    nodeResults.add(nodeResult);
                }
            }
            
            outputData = currentInput;
            
        } catch (Exception e) {
            status = "FAILED";
            if (errorMessage == null) {
                errorMessage = e.getMessage();
            }
            if (!(e instanceof NodeExecutionException)) {
                errorLogs.add(buildWorkflowErrorLog(e));
            }
        }
        
        long endTime = System.currentTimeMillis();
        int duration = (int) (endTime - startTime);
        
        if (eventCallback != null) {
            eventCallback.accept(ExecutionEvent.workflowComplete(status, currentInput, duration));
        }
        
        log.debug("保存执行记录 - inputData: {}", record.getInputData());
        log.debug("保存执行记录 - outputData: {}", outputData);
        record.setOutputData(outputData == null ? null : JSON.toJSONString(outputData));
        record.setStatus(status);
        record.setNodeResults(JSON.toJSONString(nodeResults));
        record.setErrorMessage(errorMessage);
        record.setErrorLog(JSON.toJSONString(errorLogs));
        record.setRetryCount(totalRetryCount);
        record.setTimeoutCount(totalTimeoutCount);
        record.setDuration(duration);
        executionRecordMapper.updateById(record);
        
        ExecutionResponse response = new ExecutionResponse();
        response.setExecutionId(record.getId());
        response.setStatus(status);
        response.setNodeResults(nodeResults);
        response.setOutputData(outputData);
        response.setDuration(duration);
        response.setErrorMessage(errorMessage);
        response.setErrorLog(record.getErrorLog());
        response.setRetryCount(totalRetryCount);
        response.setTimeoutCount(totalTimeoutCount);
        
        return response;
    }
    
    @Override
    public String getEngineType() {
        return "dag";
    }

    private ExecutionRecord createRunningRecord(Workflow workflow, String inputData) {
        ExecutionRecord record = new ExecutionRecord();
        record.setFlowId(workflow.getId());
        Map<String, Object> inputDataMap = new HashMap<>();
        inputDataMap.put("input", inputData);
        record.setInputData(JSON.toJSONString(inputDataMap));
        record.setStatus("RUNNING");
        record.setNodeResults(JSON.toJSONString(new ArrayList<>()));
        record.setDuration(0);
        return record;
    }

    private int countTimeouts(List<NodeExecutionAttempt> attempts) {
        if (attempts == null) {
            return 0;
        }
        int count = 0;
        for (NodeExecutionAttempt attempt : attempts) {
            if ("TIMEOUT".equals(attempt.getErrorType())) {
                count++;
            }
        }
        return count;
    }

    private void appendFailedAttempts(
            List<Map<String, Object>> errorLogs,
            WorkflowNode node,
            List<NodeExecutionAttempt> attempts
    ) {
        if (attempts == null) {
            return;
        }
        for (NodeExecutionAttempt attempt : attempts) {
            if (!"FAILED".equals(attempt.getStatus())) {
                continue;
            }
            Map<String, Object> logEntry = new LinkedHashMap<>();
            logEntry.put("nodeId", node.getId());
            logEntry.put("nodeName", node.getType());
            logEntry.put("attempt", attempt.getAttempt());
            logEntry.put("errorType", attempt.getErrorType());
            logEntry.put("message", attempt.getMessage());
            logEntry.put("duration", attempt.getDuration());
            logEntry.put("timestamp", attempt.getTimestamp());
            errorLogs.add(logEntry);
        }
    }

    private Map<String, Object> buildWorkflowErrorLog(Exception e) {
        Map<String, Object> logEntry = new LinkedHashMap<>();
        logEntry.put("scope", "workflow");
        logEntry.put("errorType", e.getClass().getSimpleName());
        logEntry.put("message", e.getMessage());
        logEntry.put("timestamp", System.currentTimeMillis());
        return logEntry;
    }

    private Map<String, Object> removeInternalContext(Map<String, Object> data) {
        Map<String, Object> cleanData = new HashMap<>();
        if (data != null) {
            cleanData.putAll(data);
        }
        cleanData.remove(NODE_OUTPUTS_CONTEXT_KEY);
        cleanData.remove(EXECUTION_USER_ID_CONTEXT_KEY);
        cleanData.remove(EXECUTION_ADMIN_CONTEXT_KEY);
        cleanData.remove(EXECUTION_FLOW_ID_CONTEXT_KEY);
        return cleanData;
    }

    private Map<String, WorkflowNode> buildNodeMap(List<WorkflowNode> nodes) {
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.getId(), node);
        }
        return nodeMap;
    }

    private Map<String, List<com.paiagent.engine.model.WorkflowEdge>> buildIncomingEdges(WorkflowConfig config) {
        Map<String, List<com.paiagent.engine.model.WorkflowEdge>> incoming = new HashMap<>();
        for (WorkflowNode node : config.getNodes()) {
            incoming.put(node.getId(), new ArrayList<>());
        }
        for (com.paiagent.engine.model.WorkflowEdge edge : config.getEdges()) {
            incoming.computeIfAbsent(edge.getTarget(), key -> new ArrayList<>()).add(edge);
        }
        return incoming;
    }

    private Map<String, List<com.paiagent.engine.model.WorkflowEdge>> buildOutgoingEdges(WorkflowConfig config) {
        Map<String, List<com.paiagent.engine.model.WorkflowEdge>> outgoing = new HashMap<>();
        for (WorkflowNode node : config.getNodes()) {
            outgoing.put(node.getId(), new ArrayList<>());
        }
        for (com.paiagent.engine.model.WorkflowEdge edge : config.getEdges()) {
            outgoing.computeIfAbsent(edge.getSource(), key -> new ArrayList<>()).add(edge);
        }
        return outgoing;
    }

    private Map<String, Object> buildNodeInput(
            WorkflowNode node,
            Workflow workflow,
            String rawInput,
            Map<String, List<com.paiagent.engine.model.WorkflowEdge>> incomingEdges,
            Map<String, Map<String, Object>> nodeOutputs,
            Set<String> activeNodes,
            Set<String> completedNodes,
            Set<String> skippedNodes
    ) {
        List<com.paiagent.engine.model.WorkflowEdge> incoming = incomingEdges.getOrDefault(node.getId(), List.of());
        Map<String, Object> input = new HashMap<>();

        if (incoming.isEmpty()) {
            input.put("input", rawInput);
        } else {
            for (com.paiagent.engine.model.WorkflowEdge edge : incoming) {
                String source = edge.getSource();
                if (!completedNodes.contains(source) && (activeNodes.contains(source) || !skippedNodes.contains(source))) {
                    continue;
                }
                Map<String, Object> sourceOutput = nodeOutputs.get(source);
                if (sourceOutput != null) {
                    input.putAll(sourceOutput);
                }
            }
            if (input.isEmpty()) {
                input.put("input", rawInput);
            }
        }

        input.put(NODE_OUTPUTS_CONTEXT_KEY, nodeOutputs);
        addExecutionContext(input, workflow);
        return input;
    }

    private void addExecutionContext(Map<String, Object> input, Workflow workflow) {
        WorkflowExecutionContextHolder.WorkflowExecutionContext context = WorkflowExecutionContextHolder.get();
        Long userId = context == null ? workflow.getOwnerId() : context.userId();
        boolean admin = context != null && context.admin();

        if (userId != null) {
            input.put(EXECUTION_USER_ID_CONTEXT_KEY, userId);
        }
        input.put(EXECUTION_ADMIN_CONTEXT_KEY, admin);
        input.put(EXECUTION_FLOW_ID_CONTEXT_KEY, workflow.getId());
    }

    private void activateTarget(
            String targetId,
            Set<String> activeNodes,
            Set<String> scheduledNodes,
            Set<String> completedNodes,
            Set<String> skippedNodes,
            Map<String, List<com.paiagent.engine.model.WorkflowEdge>> incomingEdges,
            Queue<String> executionQueue
    ) {
        if (completedNodes.contains(targetId) || skippedNodes.contains(targetId)) {
            return;
        }
        activeNodes.add(targetId);
        if (!scheduledNodes.contains(targetId) && isReady(targetId, activeNodes, completedNodes, skippedNodes, incomingEdges)) {
            scheduledNodes.add(targetId);
            executionQueue.offer(targetId);
        }
    }

    private boolean isReady(
            String nodeId,
            Set<String> activeNodes,
            Set<String> completedNodes,
            Set<String> skippedNodes,
            Map<String, List<com.paiagent.engine.model.WorkflowEdge>> incomingEdges
    ) {
        for (com.paiagent.engine.model.WorkflowEdge edge : incomingEdges.getOrDefault(nodeId, List.of())) {
            String source = edge.getSource();
            if (activeNodes.contains(source) && !completedNodes.contains(source)) {
                return false;
            }
            if (!activeNodes.contains(source) && !skippedNodes.contains(source)) {
                continue;
            }
        }
        return true;
    }

    private String resolveSelectedBranch(Map<String, Object> output) {
        Object selectedBranch = output.get("selectedBranch");
        if (selectedBranch != null) {
            return String.valueOf(selectedBranch).toLowerCase();
        }
        Object conditionResult = output.get("conditionResult");
        if (conditionResult instanceof Boolean result) {
            return result ? "true" : "false";
        }
        return "false";
    }

    private List<com.paiagent.engine.model.WorkflowEdge> selectConditionEdges(
            List<com.paiagent.engine.model.WorkflowEdge> outgoing,
            String selectedBranch
    ) {
        List<com.paiagent.engine.model.WorkflowEdge> selectedEdges = outgoing.stream()
                .filter(edge -> branchMatches(edge.getSourceHandle(), selectedBranch))
                .toList();
        if (!selectedEdges.isEmpty()) {
            return selectedEdges;
        }

        boolean hasExplicitHandle = outgoing.stream().anyMatch(edge -> edge.getSourceHandle() != null && !edge.getSourceHandle().isBlank());
        if (!hasExplicitHandle && !outgoing.isEmpty()) {
            if ("true".equals(selectedBranch)) {
                return List.of(outgoing.get(0));
            }
            if (outgoing.size() > 1) {
                return List.of(outgoing.get(1));
            }
        }
        return List.of();
    }

    private boolean branchMatches(String sourceHandle, String selectedBranch) {
        if (sourceHandle == null) {
            return false;
        }
        String normalized = sourceHandle.toLowerCase();
        if ("true".equals(selectedBranch)) {
            return normalized.equals("true") || normalized.contains("true") || normalized.equals("yes");
        }
        return normalized.equals("false") || normalized.contains("false") || normalized.equals("no") || normalized.equals("else");
    }

    private void skipInactiveBranch(
            String nodeId,
            Set<String> activeNodes,
            Set<String> completedNodes,
            Set<String> skippedNodes,
            Map<String, List<com.paiagent.engine.model.WorkflowEdge>> incomingEdges,
            Map<String, List<com.paiagent.engine.model.WorkflowEdge>> outgoingEdges
    ) {
        if (completedNodes.contains(nodeId) || activeNodes.contains(nodeId) || skippedNodes.contains(nodeId)) {
            return;
        }

        skippedNodes.add(nodeId);
        for (com.paiagent.engine.model.WorkflowEdge edge : outgoingEdges.getOrDefault(nodeId, List.of())) {
            String target = edge.getTarget();
            boolean hasLiveIncoming = incomingEdges.getOrDefault(target, List.of()).stream()
                    .map(com.paiagent.engine.model.WorkflowEdge::getSource)
                    .anyMatch(source -> activeNodes.contains(source) || completedNodes.contains(source));
            if (!hasLiveIncoming) {
                skipInactiveBranch(target, activeNodes, completedNodes, skippedNodes, incomingEdges, outgoingEdges);
            }
        }
    }
}
