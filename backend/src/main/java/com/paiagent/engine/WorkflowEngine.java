package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.dag.DAGParser;
import com.paiagent.engine.execution.NodeExecutionAttempt;
import com.paiagent.engine.execution.NodeExecutionException;
import com.paiagent.engine.execution.NodeExecutionOutcome;
import com.paiagent.engine.execution.NodeExecutionRunner;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.ExecutionRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class WorkflowEngine implements WorkflowExecutor {
    
    @Autowired
    private DAGParser dagParser;
    
    @Autowired
    private NodeExecutionRunner nodeExecutionRunner;
    
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
        String outputData = null;
        int totalRetryCount = 0;
        int totalTimeoutCount = 0;
        
        ExecutionRecord record = createRunningRecord(workflow, inputData);
        executionRecordMapper.insert(record);
        
        try {
            if (eventCallback != null) {
                eventCallback.accept(ExecutionEvent.workflowStart(record.getId()));
            }

            WorkflowConfig config = JSON.parseObject(workflow.getFlowData(), WorkflowConfig.class);
            List<WorkflowNode> sortedNodes = dagParser.parse(config);
            
            for (WorkflowNode node : sortedNodes) {
                long nodeStartTime = System.currentTimeMillis();
                
                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeStart(node.getId(), node.getType()));
                }
                
                ExecutionResponse.NodeResult nodeResult = new ExecutionResponse.NodeResult();
                nodeResult.setNodeId(node.getId());
                nodeResult.setNodeName(node.getType());
                nodeResult.setInput(JSON.toJSONString(currentInput));
                
                try {
                    NodeExecutionOutcome outcome = nodeExecutionRunner.execute(node, currentInput, eventCallback);
                    Map<String, Object> output = outcome.getOutput();

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
                        eventData.put("input", currentInput);
                        eventData.put("output", output);
                        eventData.put("duration", nodeDuration);
                        eventData.put("attempts", outcome.getAttempts().size());
                        eventData.put("retryCount", outcome.getRetryCount());
                        eventData.put("timeoutMs", outcome.getTimeoutMs());
                        eventCallback.accept(ExecutionEvent.nodeSuccess(node.getId(), node.getType(), eventData, nodeDuration));
                    }
                    
                    currentInput = output;
                    
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
            
            outputData = JSON.toJSONString(currentInput);
            
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
        
        log.info("保存执行记录 - inputData: {}", record.getInputData());
        log.info("保存执行记录 - outputData: {}", outputData);
        record.setOutputData(outputData);
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
}
