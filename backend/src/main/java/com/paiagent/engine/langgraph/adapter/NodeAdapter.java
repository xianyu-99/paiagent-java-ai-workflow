package com.paiagent.engine.langgraph.adapter;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.execution.NodeExecutionOutcome;
import com.paiagent.engine.execution.NodeExecutionRunner;
import com.paiagent.engine.langgraph.WorkflowState;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 节点适配器
 * 
 * 将现有的 NodeExecutor 适配为 LangGraph NodeAction
 * 实现节点执行逻辑与 LangGraph 状态模型的桥接
 */
@Slf4j
@Component
public class NodeAdapter {

    private static final String EXECUTION_USER_ID_CONTEXT_KEY = "__executionUserId__";
    private static final String EXECUTION_ADMIN_CONTEXT_KEY = "__executionAdmin__";
    
    @Autowired
    private NodeExecutionRunner nodeExecutionRunner;
    
    /**
     * 将 WorkflowNode 适配为 LangGraph AsyncNodeAction
     * 
     * @param node 工作流节点定义
     * @param eventCallback 事件回调函数（可选）
     * @return LangGraph AsyncNodeAction
     */
    public AsyncNodeAction<AgentState> adaptNode(WorkflowNode node, Consumer<ExecutionEvent> eventCallback) {
        
        return (AgentState state) -> {
            long startTime = System.currentTimeMillis();
            
            try {
                // 触发节点开始事件
                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeStart(node.getId(), node.getType()));
                }
                
                // 从状态中提取当前输入
                Map<String, Object> stateData = state.data();
                @SuppressWarnings("unchecked")
                Map<String, Object> stateCurrentInput = (Map<String, Object>) stateData.getOrDefault("currentInput", new HashMap<>());
                Map<String, Object> currentInput = new HashMap<>(stateCurrentInput);
                
                @SuppressWarnings("unchecked")
                Map<String, Map<String, Object>> stateNodeOutputs =
                    (Map<String, Map<String, Object>>) stateData.getOrDefault("nodeOutputs", new HashMap<>());
                Map<String, Map<String, Object>> nodeOutputs = new HashMap<>(stateNodeOutputs);

                @SuppressWarnings("unchecked")
                Map<String, Integer> stateNodeExecutionCounts =
                    (Map<String, Integer>) stateData.getOrDefault("nodeExecutionCounts", new HashMap<>());
                Map<String, Integer> nodeExecutionCounts = new HashMap<>(stateNodeExecutionCounts);
                int executionCount = nodeExecutionCounts.getOrDefault(node.getId(), 0) + 1;

                currentInput.put("__nodeOutputs__", nodeOutputs);
                currentInput.put("__nodeExecutionCount__", executionCount);
                currentInput.put("executionCount", executionCount);
                currentInput.put("loopIteration", executionCount);
                
                // 执行节点，复用统一的超时和重试策略
                NodeExecutionOutcome outcome = nodeExecutionRunner.execute(node, currentInput, eventCallback);
                Map<String, Object> output = removeInternalContext(outcome.getOutput());
                
                // 更新状态
                Map<String, Object> newStateData = new HashMap<>(stateData);
                
                // 保存节点输出
                nodeOutputs.put(node.getId(), output);
                newStateData.put("nodeOutputs", nodeOutputs);
                nodeExecutionCounts.put(node.getId(), executionCount);
                newStateData.put("nodeExecutionCounts", nodeExecutionCounts);
                
                // 更新当前输入为本节点输出（传递给下一个节点）
                newStateData.put("currentInput", output);
                newStateData.put("currentNodeId", node.getId());
                
                // 触发节点成功事件
                if (eventCallback != null) {
                    long duration = System.currentTimeMillis() - startTime;
                    Map<String, Object> eventData = new HashMap<>();
                    eventData.put("input", removeInternalContext(currentInput));
                    eventData.put("output", output);
                    eventData.put("duration", duration);
                    eventData.put("attempts", outcome.getAttempts().size());
                    eventData.put("retryCount", outcome.getRetryCount());
                    eventData.put("timeoutMs", outcome.getTimeoutMs());
                    eventCallback.accept(ExecutionEvent.nodeSuccess(node.getId(), node.getType(), eventData, (int) duration));
                }
                
                return CompletableFuture.completedFuture(newStateData);
                
            } catch (Exception e) {
                log.error("节点执行失败: {}", node.getId(), e);
                
                // 触发节点错误事件
                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeError(node.getId(), node.getType(), e.getMessage()));
                }
                
                // 更新状态为失败
                Map<String, Object> errorState = new HashMap<>(state.data());
                errorState.put("status", "FAILED");
                errorState.put("errorMessage", "节点 " + node.getId() + " 执行失败: " + e.getMessage());
                
                return CompletableFuture.completedFuture(errorState);
            }
        };
    }

    private Map<String, Object> removeInternalContext(Map<String, Object> data) {
        Map<String, Object> cleanData = new HashMap<>();
        if (data != null) {
            cleanData.putAll(data);
        }
        cleanData.remove("__nodeOutputs__");
        cleanData.remove("__nodeExecutionCount__");
        cleanData.remove(EXECUTION_USER_ID_CONTEXT_KEY);
        cleanData.remove(EXECUTION_ADMIN_CONTEXT_KEY);
        return cleanData;
    }
    
    /**
     * 批量适配多个节点
     * 
     * @param nodes 节点列表
     * @param eventCallback 事件回调
     * @return 节点ID到AsyncNodeAction的映射
     */
    public Map<String, AsyncNodeAction<AgentState>> adaptNodes(
            java.util.List<WorkflowNode> nodes, 
            Consumer<ExecutionEvent> eventCallback) {
        
        Map<String, AsyncNodeAction<AgentState>> adaptedNodes = new HashMap<>();
        
        for (WorkflowNode node : nodes) {
            adaptedNodes.put(node.getId(), adaptNode(node, eventCallback));
        }
        
        return adaptedNodes;
    }
}
