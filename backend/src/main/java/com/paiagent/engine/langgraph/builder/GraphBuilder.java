package com.paiagent.engine.langgraph.builder;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.langgraph.adapter.NodeAdapter;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 图构建器
 * 
 * 将工作流配置转换为 LangGraph StateGraph
 * 负责节点注册、边添加、入口出口设置
 */
@Slf4j
@Component
public class GraphBuilder {

    private static final int DEFAULT_MAX_ITERATIONS = 100;
    
    @Autowired
    private NodeAdapter nodeAdapter;
    
    /**
     * 构建 LangGraph StateGraph
     * 
     * @param config 工作流配置
     * @param eventCallback 事件回调（可选）
     * @return 编译后的 StateGraph
     * @throws Exception 图构建异常
     */
    public org.bsc.langgraph4j.CompiledGraph<AgentState> buildGraph(
            WorkflowConfig config, 
            Consumer<ExecutionEvent> eventCallback) throws Exception {
        
        log.info("开始构建 LangGraph: 节点数={}, 边数={}", 
            config.getNodes().size(), config.getEdges().size());
        
        // 创建 StateGraph
        StateGraph<AgentState> graph = new StateGraph<>(AgentState::new);
        
        // 添加所有节点
        addNodes(graph, config.getNodes(), eventCallback);
        
        Map<String, WorkflowNode> nodeMap = buildNodeMap(config.getNodes());

        // 添加普通边；条件节点出边要用 LangGraph 条件边表达
        addEdges(graph, config.getEdges(), nodeMap);

        // 添加条件分支边
        addConditionalEdges(graph, config.getNodes(), config.getEdges());
        
        // 设置入口和出口
        setEntryAndExit(graph, config.getNodes(), config.getEdges());
        
        // 编译图
        var compiled = graph.compile();
        compiled.setMaxIterations(DEFAULT_MAX_ITERATIONS);
        
        log.info("LangGraph 构建完成");
        return compiled;
    }
    
    /**
     * 添加节点到图中
     */
    private void addNodes(
            StateGraph<AgentState> graph, 
            List<WorkflowNode> nodes,
            Consumer<ExecutionEvent> eventCallback) throws Exception {
        
        for (WorkflowNode node : nodes) {
            log.debug("添加节点: id={}, type={}", node.getId(), node.getType());
            
            // 使用 NodeAdapter 将节点适配为 LangGraph NodeAction
            var nodeAction = nodeAdapter.adaptNode(node, eventCallback);
            
            graph.addNode(node.getId(), nodeAction);
        }
    }
    
    /**
     * 添加边到图中
     */
    private void addEdges(
            StateGraph<AgentState> graph,
            List<WorkflowEdge> edges,
            Map<String, WorkflowNode> nodeMap
    ) throws Exception {
        for (WorkflowEdge edge : edges) {
            WorkflowNode sourceNode = nodeMap.get(edge.getSource());
            if (sourceNode != null && "condition".equals(sourceNode.getType())) {
                continue;
            }
            log.debug("添加边: {} -> {}", edge.getSource(), edge.getTarget());
            graph.addEdge(edge.getSource(), edge.getTarget());
        }
    }

    /**
     * 条件节点使用 LangGraph 原生 conditional edge。
     */
    private void addConditionalEdges(
            StateGraph<AgentState> graph,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges
    ) throws Exception {
        for (WorkflowNode node : nodes) {
            if (!"condition".equals(node.getType())) {
                continue;
            }

            List<WorkflowEdge> outgoingEdges = edges.stream()
                    .filter(edge -> edge.getSource().equals(node.getId()))
                    .toList();
            if (outgoingEdges.isEmpty()) {
                graph.addEdge(node.getId(), StateGraph.END);
                continue;
            }

            Map<String, String> branchTargets = buildBranchTargets(outgoingEdges);
            graph.addConditionalEdges(node.getId(), conditionRouter(), branchTargets);
            log.debug("添加条件边: {} -> {}", node.getId(), branchTargets);
        }
    }
    
    /**
     * 设置图的入口和出口节点
     */
    private void setEntryAndExit(
            StateGraph<AgentState> graph,
            List<WorkflowNode> nodes,
            List<WorkflowEdge> edges) throws Exception {
        
        // 找到入口节点（没有前置节点的节点）
        WorkflowNode entryNode = findEntryNode(nodes, edges);
        if (entryNode != null) {
            log.info("设置入口节点: {}", entryNode.getId());
            graph.addEdge(StateGraph.START, entryNode.getId());
        } else {
            log.warn("未找到入口节点，使用第一个节点作为入口");
            if (!nodes.isEmpty()) {
                graph.addEdge(StateGraph.START, nodes.get(0).getId());
            }
        }
        
        // 找到所有出口节点（没有后继节点的节点）
        List<WorkflowNode> exitNodes = findExitNodes(nodes, edges);
        if (!exitNodes.isEmpty()) {
            for (WorkflowNode exitNode : exitNodes) {
                log.info("设置出口节点: {}", exitNode.getId());
                graph.addEdge(exitNode.getId(), StateGraph.END);
            }
        } else {
            log.warn("未找到出口节点，工作流可能包含循环；请确保条件分支能路由到 END");
        }
    }
    
    /**
     * 查找入口节点（没有前置节点）
     */
    private WorkflowNode findEntryNode(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        for (WorkflowNode node : nodes) {
            boolean hasIncomingEdge = edges.stream()
                .anyMatch(edge -> edge.getTarget().equals(node.getId()));
            
            if (!hasIncomingEdge) {
                return node;
            }
        }
        return null;
    }
    
    /**
     * 查找出口节点（没有后继节点）
     */
    private List<WorkflowNode> findExitNodes(List<WorkflowNode> nodes, List<WorkflowEdge> edges) {
        return nodes.stream()
                .filter(node -> edges.stream().noneMatch(edge -> edge.getSource().equals(node.getId())))
                .toList();
    }

    private Map<String, WorkflowNode> buildNodeMap(List<WorkflowNode> nodes) {
        Map<String, WorkflowNode> nodeMap = new HashMap<>();
        for (WorkflowNode node : nodes) {
            nodeMap.put(node.getId(), node);
        }
        return nodeMap;
    }

    private Map<String, String> buildBranchTargets(List<WorkflowEdge> outgoingEdges) {
        Map<String, String> branchTargets = new LinkedHashMap<>();

        for (int i = 0; i < outgoingEdges.size(); i++) {
            WorkflowEdge edge = outgoingEdges.get(i);
            String branch = normalizeBranch(edge.getSourceHandle());
            if (branch == null && i == 0) {
                branch = "true";
            } else if (branch == null && i == 1) {
                branch = "false";
            }

            if (branch != null) {
                branchTargets.put(branch, edge.getTarget());
            }
        }

        branchTargets.putIfAbsent("true", StateGraph.END);
        branchTargets.putIfAbsent("false", StateGraph.END);
        return branchTargets;
    }

    private String normalizeBranch(String sourceHandle) {
        if (sourceHandle == null || sourceHandle.isBlank()) {
            return null;
        }
        String normalized = sourceHandle.toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.contains("true") || normalized.equals("yes")) {
            return "true";
        }
        if (normalized.equals("false") || normalized.contains("false") || normalized.equals("no") || normalized.equals("else")) {
            return "false";
        }
        return null;
    }

    private AsyncEdgeAction<AgentState> conditionRouter() {
        return state -> CompletableFuture.completedFuture(resolveSelectedBranch(state));
    }

    private String resolveSelectedBranch(AgentState state) {
        @SuppressWarnings("unchecked")
        Map<String, Object> currentInput = (Map<String, Object>) state.data().getOrDefault("currentInput", Map.of());
        Object selectedBranch = currentInput.get("selectedBranch");
        if (selectedBranch != null) {
            return String.valueOf(selectedBranch).toLowerCase();
        }

        Object conditionResult = currentInput.get("conditionResult");
        if (conditionResult instanceof Boolean result) {
            return result ? "true" : "false";
        }
        return "false";
    }
}
