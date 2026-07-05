package com.paiagent.engine.agent.memory;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.engine.agent.context.ContextCompressionResult;
import com.paiagent.engine.agent.context.ContextCompressor;
import com.paiagent.entity.ExecutionRecord;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.ExecutionRecordMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.service.TextEmbeddingService;
import com.paiagent.service.vector.KnowledgeVectorStore;
import com.paiagent.service.vector.VectorSearchHit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Agent 长期记忆服务
 * 负责从知识库和历史执行记录中检索相关上下文，注入 Agent 的 ReAct 循环
 */
@Slf4j
@Service
public class AgentMemoryService {

    private static final int MAX_EXECUTION_RECORDS_TO_EMBED = 50;
    private static final int MAX_MEMORY_CONTEXT_LENGTH = 2000;

    private final TextEmbeddingService textEmbeddingService;
    private final KnowledgeVectorStore vectorStore;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final ExecutionRecordMapper executionRecordMapper;
    private final ContextCompressor contextCompressor;

    public AgentMemoryService(TextEmbeddingService textEmbeddingService,
                              KnowledgeVectorStore vectorStore,
                              KnowledgeChunkMapper knowledgeChunkMapper,
                              ExecutionRecordMapper executionRecordMapper,
                              ContextCompressor contextCompressor) {
        this.textEmbeddingService = textEmbeddingService;
        this.vectorStore = vectorStore;
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.executionRecordMapper = executionRecordMapper;
        this.contextCompressor = contextCompressor;
    }

    /**
     * 从知识库检索相关记忆
     *
     * @param query           查询文本
     * @param knowledgeBaseId 知识库 ID
     * @param topK            返回最大结果数
     * @param minScore        最小相似度阈值
     * @return 格式化后的知识上下文字符串，无结果返回空字符串
     */
    public String retrieveKnowledgeMemory(String query, Long knowledgeBaseId, int topK, double minScore) {
        if (knowledgeBaseId == null) {
            return "";
        }

        try {
            List<Double> queryEmbedding = textEmbeddingService.embed(query);
            List<VectorSearchHit> hits = vectorStore.search(knowledgeBaseId, queryEmbedding, topK, minScore);

            if (hits == null || hits.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            int index = 1;
            for (VectorSearchHit hit : hits) {
                KnowledgeChunk chunk = knowledgeChunkMapper.selectById(hit.chunkId());
                if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) {
                    continue;
                }
                sb.append("[Knowledge ").append(index).append("] (score: ")
                        .append(String.format("%.2f", hit.score())).append(")\n")
                        .append(chunk.getContent()).append("\n\n");
                index++;
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.error("检索知识库记忆失败, knowledgeBaseId={}, query={}", knowledgeBaseId, query, e);
            return "";
        }
    }

    private static final double DEFAULT_EXECUTION_MIN_SCORE = 0.3;

    /**
     * 从历史执行记录检索相关记忆
     *
     * @param query    查询文本
     * @param flowId   工作流 ID
     * @param topK     返回最大结果数
     * @return 格式化后的执行历史字符串，无结果返回空字符串
     */
    public String retrieveExecutionMemory(String query, Long flowId, int topK) {
        return retrieveExecutionMemory(query, flowId, topK, DEFAULT_EXECUTION_MIN_SCORE);
    }

    /**
     * 从历史执行记录检索相关记忆
     *
     * @param query    查询文本
     * @param flowId   工作流 ID
     * @param topK     返回最大结果数
     * @param minScore 最小相似度阈值
     * @return 格式化后的执行历史字符串，无结果返回空字符串
     */
    public String retrieveExecutionMemory(String query, Long flowId, int topK, double minScore) {
        if (flowId == null) {
            return "";
        }

        try {
            List<ExecutionRecord> records = executionRecordMapper.selectList(
                    new LambdaQueryWrapper<ExecutionRecord>()
                            .eq(ExecutionRecord::getFlowId, flowId)
                            .eq(ExecutionRecord::getStatus, "SUCCESS")
                            .orderByDesc(ExecutionRecord::getExecutedAt)
                            .last("LIMIT " + MAX_EXECUTION_RECORDS_TO_EMBED)
            );

            if (records == null || records.isEmpty()) {
                return "";
            }

            List<ExecutionMemory> memories = embedExecutionRecords(records, query);

            memories.sort(Comparator.comparingDouble(ExecutionMemory::similarity).reversed());

            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (ExecutionMemory mem : memories) {
                if (count >= topK) {
                    break;
                }
                if (mem.similarity() < minScore) {
                    break;
                }
                sb.append("[Past Execution ").append(count + 1).append("] (similarity: ")
                        .append(String.format("%.2f", mem.similarity())).append(")\n")
                        .append("Input: ").append(mem.input()).append("\n")
                        .append("Output: ").append(mem.output()).append("\n\n");
                count++;
            }

            return sb.toString().trim();
        } catch (Exception e) {
            log.error("检索执行历史记忆失败, flowId={}, query={}", flowId, query, e);
            return "";
        }
    }

    /**
     * 批量嵌入执行记录并计算与查询的相似度
     */
    private List<ExecutionMemory> embedExecutionRecords(List<ExecutionRecord> records, String query) {
        List<String> texts = new ArrayList<>(records.size());
        List<ExecutionMemoryBuilder> builders = new ArrayList<>(records.size());

        for (ExecutionRecord record : records) {
            String inputText = extractText(record.getInputData());
            String outputText = extractText(record.getOutputData());
            String combined = (inputText + " " + outputText).trim();
            if (combined.isEmpty()) {
                continue;
            }
            texts.add(combined);
            builders.add(new ExecutionMemoryBuilder(inputText, outputText));
        }

        if (texts.isEmpty()) {
            return List.of();
        }

        List<Double> queryEmbedding = textEmbeddingService.embed(query);
        List<List<Double>> recordEmbeddings = textEmbeddingService.embedBatch(texts);

        List<ExecutionMemory> memories = new ArrayList<>(builders.size());
        for (int i = 0; i < builders.size(); i++) {
            double similarity = textEmbeddingService.cosine(queryEmbedding, recordEmbeddings.get(i));
            memories.add(builders.get(i).build(similarity));
        }
        return memories;
    }

    /**
     * 执行记忆构建器（避免在批量嵌入前创建完整对象）
     */
    private static class ExecutionMemoryBuilder {
        private final String input;
        private final String output;

        ExecutionMemoryBuilder(String input, String output) {
            this.input = input;
            this.output = output;
        }

        ExecutionMemory build(double similarity) {
            return new ExecutionMemory(input, output, similarity);
        }
    }

    /**
     * 构建完整的记忆上下文
     *
     * @param query                  查询文本
     * @param knowledgeBaseId        知识库 ID
     * @param flowId                 工作流 ID
     * @param enableExecutionMemory  是否启用执行历史记忆
     * @param topK                   返回最大结果数
     * @param minScore               知识检索最小相似度阈值
     * @return 组合后的记忆上下文字符串，两者皆空返回 null
     */
    public MemoryContextResult buildMemoryContextWithMetadata(String query, Long knowledgeBaseId, Long flowId,
                                                              boolean enableExecutionMemory, int topK, double minScore) {
        String knowledgeMemory = retrieveKnowledgeMemory(query, knowledgeBaseId, topK, minScore);
        String executionMemory = enableExecutionMemory
                ? retrieveExecutionMemory(query, flowId, topK)
                : "";

        boolean hasKnowledge = !knowledgeMemory.isEmpty();
        boolean hasExecution = !executionMemory.isEmpty();

        if (!hasKnowledge && !hasExecution) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        if (hasKnowledge) {
            sb.append("=== Knowledge Context ===\n").append(knowledgeMemory);
        }
        if (hasExecution) {
            if (hasKnowledge) {
                sb.append("\n\n");
            }
            sb.append("=== Execution History ===\n").append(executionMemory);
        }

        String result = sb.toString();
        ContextCompressionResult compressed = contextCompressor.compress(result, query, MAX_MEMORY_CONTEXT_LENGTH);
        if (compressed.compressed()) {
            log.warn("Agent memory context compressed: originalChars={}, compressedChars={}, droppedLines={}",
                    compressed.originalLength(), compressed.compressedLength(), compressed.droppedLineCount());
        }
        return new MemoryContextResult(
                compressed.content(),
                compressed.compressed(),
                compressed.compressionRatio(),
                compressed.originalLength(),
                compressed.compressedLength(),
                compressed.droppedLineCount()
        );
    }

    public String buildMemoryContext(String query, Long knowledgeBaseId, Long flowId,
                                     boolean enableExecutionMemory, int topK, double minScore) {
        MemoryContextResult result = buildMemoryContextWithMetadata(
                query, knowledgeBaseId, flowId, enableExecutionMemory, topK, minScore);
        return result == null ? null : result.content();
    }

    /**
     * 从 JSON 字符串中提取文本内容
     * 如果是对象/数组则序列化为紧凑字符串，否则直接返回原值
     */
    private String extractText(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        try {
            Object parsed = JSON.parse(json);
            if (parsed == null) {
                return "";
            }
            return JSON.toJSONString(parsed);
        } catch (Exception e) {
            return json.trim();
        }
    }

    /**
     * 执行记忆内部记录
     */
    private record ExecutionMemory(String input, String output, double similarity) {
    }

    public record MemoryContextResult(
            String content,
            boolean compressed,
            double compressionRatio,
            int originalLength,
            int compressedLength,
            int droppedLineCount
    ) {
    }
}
