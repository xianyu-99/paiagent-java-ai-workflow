package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Query Expansion 节点执行器
 * 将用户原始问题扩展为多个语义相近的问法，以提升 RAG 检索召回率。
 */
@Component
public class QueryExpansionNodeExecutor extends AbstractLLMNodeExecutor {

    private static final String EXPANSION_PROMPT = """
            你是一个专业的问题改写助手。请将下面的用户问题改写为 %d 个语义相同但措辞不同的问法。
            要求：
            1. 每个改写版本必须保留原意，不能添加或删除关键信息。
            2. 改写应涵盖不同角度：口语化表达、正式表达、从结果角度提问等。
            3. 必须只输出一个 JSON 数组，数组包含改写后的字符串，不含原始问题，不要输出任何其他内容。
            
            示例输出格式：
            ["改写版本1", "改写版本2", "改写版本3"]
            
            用户问题：%s
            """;

    @Override
    protected String getNodeType() {
        return "query_expansion";
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node,
                                       Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        int expansionCount = toInt(data.get("expansionCount"), 3);

        // 获取原始问题
        String originalQuery = resolveQuery(input);
        if (!StringUtils.hasText(originalQuery)) {
            throw new IllegalArgumentException("Query Expansion 节点：输入问题不能为空");
        }

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(), node.getType(),
                    "正在扩展查询：" + originalQuery,
                    Map.of("originalQuery", originalQuery, "expansionCount", expansionCount)
            ));
        }

        // 构建临时 LLM 节点来调用扩展
        String prompt = String.format(EXPANSION_PROMPT, expansionCount, originalQuery);
        WorkflowNode llmNode = buildLlmNode(node, data, prompt);

        Map<String, Object> llmInput = new HashMap<>(input);
        llmInput.put("input", originalQuery);

        Map<String, Object> llmOutput = super.execute(llmNode, llmInput, null);
        String rawOutput = extractStringOutput(llmOutput);

        // 解析 LLM 返回的 JSON 数组
        List<String> expandedQueries = parseExpandedQueries(rawOutput, originalQuery);

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(), node.getType(),
                    "查询扩展完成，生成 " + expandedQueries.size() + " 个变体",
                    Map.of("expandedQueries", expandedQueries)
            ));
        }

        // 输出：保留原始 query，并附上扩展版本
        Map<String, Object> output = new HashMap<>(input);
        output.put("originalQuery", originalQuery);
        output.put("expandedQueries", expandedQueries);
        // 兼容下游 RAG 节点读取 output/input 的习惯
        output.put("output", originalQuery);
        output.put("input", originalQuery);
        return output;
    }

    /**
     * 构建用于调用 LLM 的临时节点
     */
    private WorkflowNode buildLlmNode(WorkflowNode node, Map<String, Object> data, String prompt) {
        Map<String, Object> llmData = new HashMap<>(data);
        llmData.put("type", "llm");
        llmData.put("prompt", prompt);
        llmData.put("inputParams", List.of(
                Map.of("name", "input", "type", "input", "value", "")
        ));
        llmData.putIfAbsent("outputParams", List.of(Map.of("name", "output", "type", "string")));

        WorkflowNode llmNode = new WorkflowNode();
        llmNode.setId(node.getId() + "_expansion_llm");
        llmNode.setType("llm");
        llmNode.setData(llmData);
        llmNode.setPosition(node.getPosition());
        return llmNode;
    }

    /**
     * 解析 LLM 输出的 JSON 数组，失败则回退到原始 query
     */
    private List<String> parseExpandedQueries(String raw, String originalQuery) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return result;
        }
        try {
            // 提取 JSON 数组部分（LLM 可能在前后加多余文字）
            int start = raw.indexOf('[');
            int end = raw.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String jsonPart = raw.substring(start, end + 1);
                JSONArray array = JSON.parseArray(jsonPart);
                for (int i = 0; i < array.size(); i++) {
                    String q = array.getString(i);
                    if (StringUtils.hasText(q) && !q.equals(originalQuery)) {
                        result.add(q.trim());
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败时静默忽略，返回空列表
        }
        return result;
    }

    private String resolveQuery(Map<String, Object> input) {
        Object output = input.get("output");
        if (output != null) return String.valueOf(output);
        Object raw = input.get("input");
        return raw == null ? null : String.valueOf(raw);
    }

    private String extractStringOutput(Map<String, Object> llmOutput) {
        Object output = llmOutput.get("output");
        return output == null ? "" : String.valueOf(output);
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
