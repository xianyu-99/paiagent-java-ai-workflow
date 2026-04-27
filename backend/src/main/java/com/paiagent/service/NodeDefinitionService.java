package com.paiagent.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.entity.NodeDefinition;
import com.paiagent.mapper.NodeDefinitionMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 节点定义服务
 */
@Service
public class NodeDefinitionService extends ServiceImpl<NodeDefinitionMapper, NodeDefinition> {
    
    /**
     * 查询所有节点定义
     */
    public List<NodeDefinition> listAllNodeDefinitions() {
        Map<String, NodeDefinition> nodeDefinitionMap = new LinkedHashMap<>();
        this.list().forEach(node -> nodeDefinitionMap.put(node.getNodeType(), node));

        nodeDefinitionMap.putIfAbsent("llm", createGenericLlmNodeDefinition());
        nodeDefinitionMap.putIfAbsent("condition", createConditionNodeDefinition());
        nodeDefinitionMap.putIfAbsent("rag", createRagNodeDefinition());

        return nodeDefinitionMap.values().stream()
                .filter(node -> node.getDeleted() == null || node.getDeleted() == 0)
                .toList();
    }
    
    /**
     * 根据节点类型查询
     */
    public NodeDefinition getByNodeType(String nodeType) {
        if ("llm".equals(nodeType)) {
            return createGenericLlmNodeDefinition();
        }
        if ("condition".equals(nodeType)) {
            return createConditionNodeDefinition();
        }
        if ("rag".equals(nodeType)) {
            return createRagNodeDefinition();
        }

        return this.lambdaQuery()
                .eq(NodeDefinition::getNodeType, nodeType)
                .one();
    }

    private NodeDefinition createGenericLlmNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("llm");
        nodeDefinition.setDisplayName("大模型");
        nodeDefinition.setCategory("LLM");
        nodeDefinition.setIcon("🤖");
        nodeDefinition.setInputSchema("{\"type\": \"object\", \"properties\": {\"input\": {\"type\": \"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\": \"object\", \"properties\": {\"output\": {\"type\": \"string\"}, \"tokens\": {\"type\": \"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\": \"object\", \"properties\": {\"provider\": {\"type\": \"string\"}, \"configId\": {\"type\": \"number\"}, \"apiKey\": {\"type\": \"string\"}, \"model\": {\"type\": \"string\"}, \"prompt\": {\"type\": \"string\"}, \"temperature\": {\"type\": \"number\", \"default\": 0.7}, \"maxTokens\": {\"type\": \"number\", \"default\": 1000}}}");
        return nodeDefinition;
    }

    private NodeDefinition createConditionNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("condition");
        nodeDefinition.setDisplayName("条件分支");
        nodeDefinition.setCategory("FLOW");
        nodeDefinition.setIcon("🔀");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"conditionResult\":{\"type\":\"boolean\"},\"selectedBranch\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"leftType\":{\"type\":\"string\",\"default\":\"reference\"},\"leftReference\":{\"type\":\"string\"},\"leftValue\":{\"type\":\"string\"},\"operator\":{\"type\":\"string\",\"default\":\"equals\"},\"rightValue\":{\"type\":\"string\"},\"caseSensitive\":{\"type\":\"boolean\",\"default\":false}}}");
        return nodeDefinition;
    }

    private NodeDefinition createRagNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("rag");
        nodeDefinition.setDisplayName("知识库问答");
        nodeDefinition.setCategory("KNOWLEDGE");
        nodeDefinition.setIcon("📚");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"output\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"retrievedChunks\":{\"type\":\"array\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"knowledgeBaseId\":{\"type\":\"number\"},\"topK\":{\"type\":\"number\",\"default\":3},\"minScore\":{\"type\":\"number\",\"default\":0},\"configId\":{\"type\":\"number\"},\"prompt\":{\"type\":\"string\"}}}");
        return nodeDefinition;
    }
}
