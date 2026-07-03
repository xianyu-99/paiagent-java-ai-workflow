package com.paiagent.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.entity.NodeDefinition;
import com.paiagent.mapper.NodeDefinitionMapper;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 节点定义服务
 */
@Service
public class NodeDefinitionService extends ServiceImpl<NodeDefinitionMapper, NodeDefinition> {

    private static final Set<String> LEGACY_PROVIDER_NODE_TYPES = Set.of(
            "openai",
            "deepseek",
            "qwen",
            "zhipu",
            "step",
            "ai_ping"
    );
    
    /**
     * 查询所有节点定义
     */
    public List<NodeDefinition> listAllNodeDefinitions() {
        Map<String, NodeDefinition> nodeDefinitionMap = new LinkedHashMap<>();
        this.list().stream()
                .filter(node -> !LEGACY_PROVIDER_NODE_TYPES.contains(node.getNodeType()))
                .forEach(node -> nodeDefinitionMap.put(node.getNodeType(), node));

        nodeDefinitionMap.put("input", createInputNodeDefinition());
        nodeDefinitionMap.put("output", createOutputNodeDefinition());
        nodeDefinitionMap.put("llm", createGenericLlmNodeDefinition());
        nodeDefinitionMap.put("condition", createConditionNodeDefinition());
        nodeDefinitionMap.put("tts", createTtsNodeDefinition());
        nodeDefinitionMap.put("rag", createRagNodeDefinition());
        nodeDefinitionMap.put("agent", createAgentNodeDefinition());
        nodeDefinitionMap.put("media", createMediaNodeDefinition());
        nodeDefinitionMap.put("hyde", createHydeNodeDefinition());
        nodeDefinitionMap.put("query_expansion", createQueryExpansionNodeDefinition());

        return nodeDefinitionMap.values().stream()
                .filter(node -> node.getDeleted() == null || node.getDeleted() == 0)
                .toList();
    }
    
    /**
     * 根据节点类型查询
     */
    public NodeDefinition getByNodeType(String nodeType) {
        if ("input".equals(nodeType)) {
            return createInputNodeDefinition();
        }
        if ("output".equals(nodeType)) {
            return createOutputNodeDefinition();
        }
        if ("llm".equals(nodeType)) {
            return createGenericLlmNodeDefinition();
        }
        if ("condition".equals(nodeType)) {
            return createConditionNodeDefinition();
        }
        if ("tts".equals(nodeType)) {
            return createTtsNodeDefinition();
        }
        if ("rag".equals(nodeType)) {
            return createRagNodeDefinition();
        }
        if ("agent".equals(nodeType)) {
            return createAgentNodeDefinition();
        }
        if ("media".equals(nodeType)) {
            return createMediaNodeDefinition();
        }
        if ("hyde".equals(nodeType)) {
            return createHydeNodeDefinition();
        }
        if ("query_expansion".equals(nodeType)) {
            return createQueryExpansionNodeDefinition();
        }

        return this.lambdaQuery()
                .eq(NodeDefinition::getNodeType, nodeType)
                .one();
    }

    private NodeDefinition createInputNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("input");
        nodeDefinition.setDisplayName("输入");
        nodeDefinition.setCategory("IO");
        nodeDefinition.setIcon("📥");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"defaultValue\":{\"type\":\"string\"}}}");
        return nodeDefinition;
    }

    private NodeDefinition createOutputNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("output");
        nodeDefinition.setDisplayName("输出");
        nodeDefinition.setCategory("IO");
        nodeDefinition.setIcon("📤");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"output\":{\"type\":\"string\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{}}");
        return nodeDefinition;
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

    private NodeDefinition createTtsNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("tts");
        nodeDefinition.setDisplayName("超拟人音频合成");
        nodeDefinition.setCategory("TOOL");
        nodeDefinition.setIcon("🔊");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"audioUrl\":{\"type\":\"string\"},\"fileName\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"},\"chunks\":{\"type\":\"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\",\"default\":\"qwen\"},\"apiUrl\":{\"type\":\"string\"},\"apiKey\":{\"type\":\"string\"},\"model\":{\"type\":\"string\",\"default\":\"qwen3-tts-flash\"},\"voice\":{\"type\":\"string\",\"default\":\"Cherry\"},\"style\":{\"type\":\"string\"},\"languageType\":{\"type\":\"string\",\"default\":\"Auto\"}}}");
        return nodeDefinition;
    }

    private NodeDefinition createRagNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("rag");
        nodeDefinition.setDisplayName("知识库问答");
        nodeDefinition.setCategory("KNOWLEDGE");
        nodeDefinition.setIcon("📚");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"output\":{\"type\":\"string\"},\"context\":{\"type\":\"string\"},\"retrievedChunks\":{\"type\":\"array\"},\"retrievedCount\":{\"type\":\"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"knowledgeBaseId\":{\"type\":\"number\"},\"topK\":{\"type\":\"number\",\"default\":3},\"minScore\":{\"type\":\"number\",\"default\":0},\"contextWindow\":{\"type\":\"number\",\"default\":1},\"contextMaxChars\":{\"type\":\"number\",\"default\":1800},\"configId\":{\"type\":\"number\"},\"prompt\":{\"type\":\"string\"}}}");
        return nodeDefinition;
    }

    private NodeDefinition createAgentNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("agent");
        nodeDefinition.setDisplayName("智能体");
        nodeDefinition.setCategory("AGENT");
        nodeDefinition.setIcon("🕵️");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"output\":{\"type\":\"string\"},\"thoughts\":{\"type\":\"array\"},\"iterations\":{\"type\":\"number\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\"},\"configId\":{\"type\":\"number\"},\"model\":{\"type\":\"string\"},\"systemPrompt\":{\"type\":\"string\"},\"temperature\":{\"type\":\"number\",\"default\":0.2},\"maxIterations\":{\"type\":\"number\",\"default\":5},\"reasoningMode\":{\"type\":\"string\",\"default\":\"react\"},\"tools\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}");
        return nodeDefinition;
    }

    private NodeDefinition createMediaNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("media");
        nodeDefinition.setDisplayName("媒体生成");
        nodeDefinition.setCategory("TOOL");
        nodeDefinition.setIcon("🎬");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"prompt\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"mediaUrl\":{\"type\":\"string\"},\"mediaType\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\",\"default\":\"openai\"},\"apiUrl\":{\"type\":\"string\"},\"apiKey\":{\"type\":\"string\"},\"model\":{\"type\":\"string\",\"default\":\"dall-e-3\"},\"resolution\":{\"type\":\"string\",\"default\":\"1024x1024\"},\"mediaType\":{\"type\":\"string\",\"default\":\"image\"}}}");
        return nodeDefinition;
    }

    private NodeDefinition createHydeNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("hyde");
        nodeDefinition.setDisplayName("HyDE 查询改写");
        nodeDefinition.setCategory("KNOWLEDGE");
        nodeDefinition.setIcon("🧭");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"originalQuery\":{\"type\":\"string\"},\"hydeQuery\":{\"type\":\"string\"},\"output\":{\"type\":\"string\"}}}");
        nodeDefinition.setConfigSchema(createGenericLlmNodeDefinition().getConfigSchema());
        return nodeDefinition;
    }

    private NodeDefinition createQueryExpansionNodeDefinition() {
        NodeDefinition nodeDefinition = new NodeDefinition();
        nodeDefinition.setNodeType("query_expansion");
        nodeDefinition.setDisplayName("查询扩展");
        nodeDefinition.setCategory("KNOWLEDGE");
        nodeDefinition.setIcon("🔎");
        nodeDefinition.setInputSchema("{\"type\":\"object\",\"properties\":{\"input\":{\"type\":\"string\"}}}");
        nodeDefinition.setOutputSchema("{\"type\":\"object\",\"properties\":{\"originalQuery\":{\"type\":\"string\"},\"expandedQueries\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}},\"output\":{\"type\":\"string\"}}}");
        nodeDefinition.setConfigSchema("{\"type\":\"object\",\"properties\":{\"provider\":{\"type\":\"string\"},\"configId\":{\"type\":\"number\"},\"apiKey\":{\"type\":\"string\"},\"model\":{\"type\":\"string\"},\"temperature\":{\"type\":\"number\",\"default\":0.2},\"expansionCount\":{\"type\":\"number\",\"default\":3}}}");
        return nodeDefinition;
    }
}
