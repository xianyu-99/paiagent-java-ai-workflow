package com.paiagent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.entity.KnowledgeGraphEntity;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeGraphEntityMapper;
import com.paiagent.service.graph.KnowledgeGraphService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@DependsOn("knowledgeBaseMigrationRunner")
@Order(100)
public class KnowledgeGraphBackfillRunner implements ApplicationRunner {

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final KnowledgeGraphEntityMapper graphEntityMapper;
    private final KnowledgeGraphService knowledgeGraphService;
    private final boolean backfillEnabled;

    public KnowledgeGraphBackfillRunner(KnowledgeChunkMapper knowledgeChunkMapper,
                                        KnowledgeGraphEntityMapper graphEntityMapper,
                                        KnowledgeGraphService knowledgeGraphService,
                                        @Value("${paiagent.rag.graph.backfill-enabled:true}") boolean backfillEnabled) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.graphEntityMapper = graphEntityMapper;
        this.knowledgeGraphService = knowledgeGraphService;
        this.backfillEnabled = backfillEnabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!backfillEnabled) {
            return;
        }
        try {
            backfillMissingDocumentGraphs();
        } catch (Exception e) {
            log.warn("Knowledge graph backfill skipped: {}", e.getMessage());
        }
    }

    private void backfillMissingDocumentGraphs() {
        List<KnowledgeChunk> chunks = knowledgeChunkMapper.selectList(new LambdaQueryWrapper<KnowledgeChunk>()
                .orderByAsc(KnowledgeChunk::getKnowledgeBaseId)
                .orderByAsc(KnowledgeChunk::getDocumentId)
                .orderByAsc(KnowledgeChunk::getChunkIndex));
        if (chunks.isEmpty()) {
            return;
        }
        Map<Long, List<KnowledgeChunk>> chunksByDocument = chunks.stream()
                .filter(chunk -> chunk.getDocumentId() != null)
                .collect(Collectors.groupingBy(
                        KnowledgeChunk::getDocumentId,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
        int rebuilt = 0;
        for (Map.Entry<Long, List<KnowledgeChunk>> entry : chunksByDocument.entrySet()) {
            Long documentId = entry.getKey();
            Long entityCount = graphEntityMapper.selectCount(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                    .eq(KnowledgeGraphEntity::getDocumentId, documentId));
            if (entityCount != null && entityCount > 0) {
                continue;
            }
            List<KnowledgeChunk> documentChunks = entry.getValue();
            Long knowledgeBaseId = documentChunks.get(0).getKnowledgeBaseId();
            knowledgeGraphService.rebuildDocumentGraph(knowledgeBaseId, documentId, documentChunks);
            rebuilt++;
        }
        if (rebuilt > 0) {
            log.info("Knowledge graph backfill completed: rebuiltDocuments={}", rebuilt);
        }
    }
}
