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
}
