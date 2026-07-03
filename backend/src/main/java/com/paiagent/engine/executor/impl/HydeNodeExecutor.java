package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * HyDE（Hypothetical Document Embeddings）节点执行器
 *
 * 原理：先让 LLM 针对用户问题生成一段"假设性回答"，
 * 然后用这段假设性回答的向量去检索知识库，而不是用原始问题的向量。
 * 由于答案和文档的语义距离远小于问题和文档的距离，召回精度显著提升。
 */
@Component
public class HydeNodeExecutor extends AbstractLLMNodeExecutor {

    private static final String HYDE_PROMPT = """
            请根据下方问题，生成一段简洁、准确的假设性回答（即使你不确定答案，也请尽力给出合理的推断）。
            这段回答将被用于语义检索，因此请使用专业、具体的描述性语言，涵盖可能与答案相关的关键词。
            
            只输出假设性回答本身，不要输出问题、解释或任何其他内容。
            
            问题：%s
            """;

    @Override
    protected String getNodeType() {
        return "hyde";
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node,
                                       Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();

        // 获取原始问题
        String originalQuery = resolveQuery(input);
        if (!StringUtils.hasText(originalQuery)) {
            throw new IllegalArgumentException("HyDE 节点：输入问题不能为空");
        }

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(), node.getType(),
                    "HyDE：正在生成假设性回答...",
                    Map.of("originalQuery", originalQuery)
            ));
        }

        // 构建 LLM 调用，生成假设性文档
        String prompt = String.format(HYDE_PROMPT, originalQuery);
        WorkflowNode llmNode = buildLlmNode(node, data, prompt);

        Map<String, Object> llmInput = new HashMap<>(input);
        llmInput.put("input", originalQuery);

        Map<String, Object> llmOutput = super.execute(llmNode, llmInput, null);
        String hydeQuery = extractStringOutput(llmOutput);

        if (!StringUtils.hasText(hydeQuery)) {
            // 生成失败时，回退到原始问题
            hydeQuery = originalQuery;
        }

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(), node.getType(),
                    "HyDE：假设性回答生成完成",
                    Map.of("hydeQuery", hydeQuery)
            ));
        }

        // 输出：同时保留原始问题和假设性文档，供下游 RAG 节点选择使用
        Map<String, Object> output = new HashMap<>(input);
        output.put("originalQuery", originalQuery);
        output.put("hydeQuery", hydeQuery);
        // 将 hydeQuery 设为默认输出，使下游 RAG 节点直接用它检索
        output.put("output", hydeQuery);
        output.put("input", hydeQuery);
        return output;
    }

    private WorkflowNode buildLlmNode(WorkflowNode node, Map<String, Object> data, String prompt) {
        Map<String, Object> llmData = new HashMap<>(data);
        llmData.put("type", "llm");
        llmData.put("prompt", prompt);
        llmData.put("inputParams", List.of(
                Map.of("name", "input", "type", "input", "value", "")
        ));
        llmData.putIfAbsent("outputParams", List.of(Map.of("name", "output", "type", "string")));

        WorkflowNode llmNode = new WorkflowNode();
        llmNode.setId(node.getId() + "_hyde_llm");
        llmNode.setType("llm");
        llmNode.setData(llmData);
        llmNode.setPosition(node.getPosition());
        return llmNode;
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
}
