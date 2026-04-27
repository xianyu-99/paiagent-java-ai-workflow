package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.service.KnowledgeBaseService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class RagNodeExecutor extends AbstractLLMNodeExecutor {

    private static final String EXECUTION_USER_ID_CONTEXT_KEY = "__executionUserId__";
    private static final String EXECUTION_ADMIN_CONTEXT_KEY = "__executionAdmin__";

    private static final String DEFAULT_PROMPT = """
            你是一个严谨的知识库问答助手。请只根据给定的知识库上下文回答问题。
            如果上下文没有相关信息，请直接说明“知识库中没有找到相关信息”，不要编造。

            【知识库上下文】
            {{context}}

            【用户问题】
            {{question}}

            请用中文给出清晰、简洁的回答。
            """;

    private final KnowledgeBaseService knowledgeBaseService;

    public RagNodeExecutor(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    protected String getNodeType() {
        return "rag";
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node,
                                       Map<String, Object> input,
                                       Consumer<ExecutionEvent> progressCallback) throws Exception {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        Long knowledgeBaseId = toLong(data.get("knowledgeBaseId"));
        int topK = toInt(data.get("topK"), 3);
        double minScore = toDouble(data.get("minScore"), 0.0);
        String question = resolveQuestion(data, input);

        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("RAG 节点问题不能为空");
        }

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(),
                    node.getType(),
                    "正在检索知识库...",
                    Map.of("knowledgeBaseId", knowledgeBaseId, "topK", topK)
            ));
        }

        Long executionUserId = toLong(input.get(EXECUTION_USER_ID_CONTEXT_KEY));
        boolean executionAdmin = toBoolean(input.get(EXECUTION_ADMIN_CONTEXT_KEY));
        List<RetrievedChunk> chunks = knowledgeBaseService.retrieveAuthorized(
                knowledgeBaseId,
                question,
                topK,
                minScore,
                executionUserId,
                executionAdmin
        );
        String context = buildContext(chunks);

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(),
                    node.getType(),
                    "知识库检索完成，命中 " + chunks.size() + " 个片段",
                    Map.of("retrieved", chunks.size())
            ));
        }

        WorkflowNode llmNode = buildSyntheticLlmNode(node, data, question, context);
        Map<String, Object> output = super.execute(llmNode, input, progressCallback);
        output.put("question", question);
        output.put("context", context);
        output.put("retrievedChunks", chunks);
        output.put("retrievedCount", chunks.size());
        return output;
    }

    private WorkflowNode buildSyntheticLlmNode(WorkflowNode node,
                                               Map<String, Object> data,
                                               String question,
                                               String context) {
        Map<String, Object> llmData = new HashMap<>(data);
        llmData.put("type", "llm");
        llmData.put("prompt", StringUtils.hasText((String) data.get("prompt"))
                ? data.get("prompt")
                : DEFAULT_PROMPT);
        llmData.put("inputParams", List.of(
                Map.of("name", "question", "type", "input", "value", question),
                Map.of("name", "context", "type", "input", "value", context)
        ));
        llmData.putIfAbsent("outputParams", List.of(Map.of("name", "output", "type", "string")));

        WorkflowNode llmNode = new WorkflowNode();
        llmNode.setId(node.getId());
        llmNode.setType("llm");
        llmNode.setData(llmData);
        llmNode.setPosition(node.getPosition());
        return llmNode;
    }

    @SuppressWarnings("unchecked")
    private String resolveQuestion(Map<String, Object> data, Map<String, Object> input) {
        List<Map<String, Object>> inputParams = (List<Map<String, Object>>) data.get("inputParams");
        if (inputParams != null) {
            for (Map<String, Object> param : inputParams) {
                if (!"question".equals(param.get("name"))) {
                    continue;
                }
                if ("input".equals(param.get("type"))) {
                    return stringValue(param.get("value"));
                }
                if ("reference".equals(param.get("type"))) {
                    Object value = resolveReference(stringValue(param.get("referenceNode")), input);
                    if (value != null) {
                        return String.valueOf(value);
                    }
                }
            }
        }

        Object output = input.get("output");
        if (output != null) {
            return String.valueOf(output);
        }
        Object rawInput = input.get("input");
        return rawInput == null ? null : String.valueOf(rawInput);
    }

    private Object resolveReference(String reference, Map<String, Object> input) {
        if (!StringUtils.hasText(reference)) {
            return null;
        }
        if (!reference.contains(".")) {
            return input.get(reference);
        }

        String[] parts = reference.split("\\.");
        String nodeId = parts[0];
        String field = parts[parts.length - 1];
        Object nodeOutputsObject = input.get("__nodeOutputs__");
        if (nodeOutputsObject instanceof Map<?, ?> nodeOutputs) {
            Object nodeOutputObject = nodeOutputs.get(nodeId);
            if (nodeOutputObject instanceof Map<?, ?> nodeOutput) {
                Object value = nodeOutput.get(field);
                if (value != null) {
                    return value;
                }
            }
        }
        if ("user_input".equals(field)) {
            return input.get("input");
        }
        return input.get(field);
    }

    private String buildContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "未检索到相关知识片段。";
        }
        return chunks.stream()
                .map(chunk -> String.format("[%s, score=%.4f, vector=%.4f, keyword=%.4f]\n%s",
                        buildCitation(chunk),
                        chunk.getScore(),
                        chunk.getVectorScore(),
                        chunk.getKeywordScore(),
                        chunk.getContent()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private String buildCitation(RetrievedChunk chunk) {
        StringBuilder citation = new StringBuilder("片段 ").append(chunk.getChunkIndex() + 1);
        if (StringUtils.hasText(chunk.getSourceName())) {
            citation.append(", file=").append(chunk.getSourceName());
        }
        if (chunk.getPageNumber() != null) {
            citation.append(", page=").append(chunk.getPageNumber());
        }
        if (StringUtils.hasText(chunk.getSectionTitle())) {
            citation.append(", section=").append(chunk.getSectionTitle());
        }
        return citation.toString();
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Long.parseLong(text);
        }
        return null;
    }

    private int toInt(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text);
        }
        return defaultValue;
    }

    private double toDouble(Object value, double defaultValue) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Double.parseDouble(text);
        }
        return defaultValue;
    }

    private boolean toBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return false;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
