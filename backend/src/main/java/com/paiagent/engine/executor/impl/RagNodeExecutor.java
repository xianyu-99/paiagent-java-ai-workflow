package com.paiagent.engine.executor.impl;

import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.reference.WorkflowReferenceResolver;
import com.paiagent.service.KnowledgeBaseService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Component
public class RagNodeExecutor extends AbstractLLMNodeExecutor {

    private static final String EXECUTION_USER_ID_CONTEXT_KEY = "__executionUserId__";
    private static final String EXECUTION_ADMIN_CONTEXT_KEY = "__executionAdmin__";

    private static final String DEFAULT_PROMPT = """
            你是企业内部服务台知识助手。请严格按 Self-RAG 流程执行：

            步骤1：逐条评估每个检索片段是否与用户问题相关（relevant/irrelevant）
            步骤2：仅基于标记为 relevant 的片段生成答案，不得引入外部知识
            步骤3：评估答案是否被检索内容充分支撑（supported/partially/unsupported）
            步骤4：如果支撑度为 unsupported 或检索片段全部 irrelevant，设置 nextAction=escalate_human

            【知识库上下文】
            {{context}}

            【用户问题】
            {{question}}

            必须只输出一个 JSON 对象，不要输出 Markdown、代码块或额外解释。字段固定为：
            {
              "answer": "面向员工的简明答复",
              "citations": ["资料名称或来源编号"],
              "confidence": 0.0-1.0,
              "resolved": true|false,
              "nextAction": "direct_answer|create_ticket|escalate_human",
              "ticketSummary": "",
              "escalationReason": "",
              "supportLevel": "supported|partially|unsupported",
              "relevanceAssessment": [{"source": "来源1", "relevant": true|false}]
            }
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
        int contextWindow = toInt(data.get("contextWindow"), 1);
        int contextMaxChars = toInt(data.get("contextMaxChars"), 1800);
        String question = resolveQuestion(data, input);

        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("RAG 节点问题不能为空");
        }
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("RAG 节点缺少知识库配置");
        }

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(),
                    node.getType(),
                    "正在检索知识库...",
                    Map.of(
                            "knowledgeBaseId", knowledgeBaseId,
                            "topK", topK,
                            "contextWindow", contextWindow,
                            "contextMaxChars", contextMaxChars
                    )
            ));
        }

        Long executionUserId = toLong(input.get(EXECUTION_USER_ID_CONTEXT_KEY));
        boolean executionAdmin = toBoolean(input.get(EXECUTION_ADMIN_CONTEXT_KEY));
        List<RetrievedChunk> chunks = knowledgeBaseService.retrieveAuthorized(
                knowledgeBaseId,
                question,
                topK,
                minScore,
                contextWindow,
                contextMaxChars,
                executionUserId,
                executionAdmin
        );
        String context = buildContext(chunks);
        boolean retrievalOnly = Boolean.TRUE.equals(data.get("retrievalOnly"));
        double topScore = chunks.isEmpty() ? 0.0 : chunks.get(0).getScore();

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(),
                    node.getType(),
                    "知识库检索完成，命中 " + chunks.size() + " 个片段",
                    Map.of(
                            "retrieved", chunks.size(),
                            "topScore", topScore
                    )
            ));
        }

        if (retrievalOnly) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("question", question);
            output.put("output", context);
            output.put("context", context);
            output.put("retrievedChunks", chunks);
            output.put("citations", buildCitations(chunks));
            output.put("retrievedCount", chunks.size());
            return output;
        }

        // Self-RAG 硬门控：检索为空或最高相似度低于阈值 → 直接拒绝回答
        if (chunks.isEmpty() || topScore < minScore) {
            Map<String, Object> output = buildRejectionOutput(question, context, chunks,
                    chunks.isEmpty() ? "检索结果为空" : "最高相似度低于阈值（" + String.format("%.4f", topScore) + " < " + minScore + "）");
            if (progressCallback != null) {
                progressCallback.accept(ExecutionEvent.nodeProgress(
                        node.getId(), node.getType(), "Self-RAG 门控：检索质量不足，已拒绝回答", Map.of()
                ));
            }
            return output;
        }

        WorkflowNode llmNode = buildSyntheticLlmNode(node, data, question, context);
        Map<String, Object> output = super.execute(llmNode, input, progressCallback);
        output = applySelfRagGuardrails(output, question, context, chunks);
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
                    Object value = WorkflowReferenceResolver.resolve(stringValue(param.get("referenceNode")), input);
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

    private String buildContext(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "未检索到相关知识片段。";
        }
        return java.util.stream.IntStream.range(0, chunks.size())
                .mapToObj(index -> {
                    RetrievedChunk chunk = chunks.get(index);
                    return String.format("[来源%d: %s, score=%.4f, vector=%.4f, keyword=%.4f, matched=%s]\n%s",
                        index + 1,
                        buildCitation(chunk),
                        chunk.getScore(),
                        chunk.getVectorScore(),
                        chunk.getKeywordScore(),
                        chunk.getMatchedTerms(),
                        StringUtils.hasText(chunk.getContextContent()) ? chunk.getContextContent() : chunk.getContent());
                })
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    private List<Map<String, Object>> buildCitations(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> citations = new ArrayList<>();
        if (chunks == null) {
            return citations;
        }

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("ref", "来源" + (i + 1));
            citation.put("chunkId", chunk.getChunkId());
            citation.put("documentId", chunk.getDocumentId());
            citation.put("chunkIndex", chunk.getChunkIndex());
            citation.put("sourceName", chunk.getSourceName());
            citation.put("sectionTitle", chunk.getSectionTitle());
            citation.put("pageNumber", chunk.getPageNumber());
            citation.put("score", chunk.getScore());
            citation.put("vectorScore", chunk.getVectorScore());
            citation.put("keywordScore", chunk.getKeywordScore());
            citation.put("matchedTerms", chunk.getMatchedTerms());
            citation.put("preview", previewText(StringUtils.hasText(chunk.getContextContent())
                    ? chunk.getContextContent()
                    : chunk.getContent()));
            citations.add(citation);
        }
        return citations;
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

    /**
     * Self-RAG 硬门控：检索质量不足时直接返回拒绝回答的结构化输出
     */
    private Map<String, Object> buildRejectionOutput(String question, String context,
                                                       List<RetrievedChunk> chunks, String reason) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("question", question);
        output.put("answer", "知识库中未检索到足够相关的内容，已为您转接人工服务。");
        output.put("output", "知识库中未检索到足够相关的内容，已为您转接人工服务。");
        output.put("context", context);
        output.put("retrievedChunks", chunks);
        output.put("citations", buildCitations(chunks));
        output.put("retrievedCount", chunks.size());
        output.put("confidence", 0.0);
        output.put("resolved", false);
        output.put("nextAction", "escalate_human");
        output.put("ticketSummary", question);
        output.put("escalationReason", reason);
        output.put("supportLevel", "unsupported");
        output.put("relevanceAssessment", List.of());
        return output;
    }

    /**
     * Self-RAG 答案后校验：解析 LLM 输出，强制降级低质量答案
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applySelfRagGuardrails(Map<String, Object> output, String question,
                                                         String context, List<RetrievedChunk> chunks) {
        output.put("question", question);
        output.put("context", context);
        output.put("retrievedChunks", chunks);
        output.put("citations", buildCitations(chunks));
        output.put("retrievedCount", chunks.size());

        Object supportLevelObj = output.get("supportLevel");
        String supportLevel = supportLevelObj instanceof String ? (String) supportLevelObj : "";
        Object confidenceObj = output.get("confidence");
        double confidence = confidenceObj instanceof Number ? ((Number) confidenceObj).doubleValue() : 0.0;

        boolean shouldEscalate = "unsupported".equalsIgnoreCase(supportLevel)
                || confidence < 0.5
                || output.get("citations") == null
                || ((List<?>) output.getOrDefault("citations", List.of())).isEmpty();

        if (shouldEscalate) {
            output.put("resolved", false);
            output.put("nextAction", "escalate_human");
            String existingReason = output.get("escalationReason") instanceof String
                    ? (String) output.get("escalationReason") : "";
            if (!StringUtils.hasText(existingReason)) {
                if ("unsupported".equalsIgnoreCase(supportLevel)) {
                    output.put("escalationReason", "答案未被检索内容支撑");
                } else if (confidence < 0.5) {
                    output.put("escalationReason", "置信度过低（" + String.format("%.2f", confidence) + "）");
                } else {
                    output.put("escalationReason", "缺少引用来源");
                }
            }
        }

        return output;
    }

    private String previewText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        int maxLength = 280;
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
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
