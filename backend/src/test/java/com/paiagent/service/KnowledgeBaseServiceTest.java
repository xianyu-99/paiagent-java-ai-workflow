package com.paiagent.service;

import com.paiagent.dto.RetrievedChunk;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.mapper.KnowledgeImportTaskMapper;
import com.paiagent.service.document.DocumentParsingService;
import com.paiagent.service.rag.RagRetrievalScorer;
import com.paiagent.service.vector.KnowledgeVectorStoreService;
import com.paiagent.service.vector.VectorSearchHit;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    @Test
    void retrieveExpandsAdjacentContextWindow() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        TextEmbeddingService embeddingService = mock(TextEmbeddingService.class);
        KnowledgeVectorStoreService vectorStoreService = mock(KnowledgeVectorStoreService.class);
        RagRetrievalScorer scorer = new RagRetrievalScorer();

        KnowledgeBaseService service = new KnowledgeBaseService(
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeDocumentMapper.class),
                chunkMapper,
                mock(KnowledgeImportTaskMapper.class),
                embeddingService,
                vectorStoreService,
                mock(DocumentParsingService.class),
                scorer,
                mock(ThreadPoolTaskExecutor.class)
        );

        KnowledgeChunk previous = chunk(1L, 1L, 10L, 0, "上一段介绍知识库准备");
        KnowledgeChunk center = chunk(2L, 1L, 10L, 1, "这一段介绍知识库导入");
        KnowledgeChunk next = chunk(3L, 1L, 10L, 2, "下一段介绍检索调试");

        when(embeddingService.embed("知识库导入")).thenReturn(List.of(0.1, 0.2));
        when(embeddingService.isCompatible(any(), any(), any())).thenReturn(true);
        when(vectorStoreService.search(eq(1L), any(), eq(13), eq(0.0)))
                .thenReturn(List.of(new VectorSearchHit(2L, 0.91)));
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(center));
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(), List.of(previous, center, next));

        List<RetrievedChunk> chunks = service.retrieve(1L, "知识库导入", 1, 0.0, 1, 2000);

        assertEquals(1, chunks.size());
        RetrievedChunk retrieved = chunks.get(0);
        assertEquals(1, retrieved.getRank());
        assertEquals(List.of(0, 1, 2), retrieved.getContextChunkIndexes());
        assertTrue(retrieved.getContextContent().contains("上一段介绍知识库准备"));
        assertTrue(retrieved.getContextContent().contains("这一段介绍知识库导入"));
        assertTrue(retrieved.getContextContent().contains("下一段介绍检索调试"));
        assertTrue(retrieved.getMatchedTerms().contains("知识库"));
    }

    private KnowledgeChunk chunk(Long id, Long knowledgeBaseId, Long documentId, int chunkIndex, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setKnowledgeBaseId(knowledgeBaseId);
        chunk.setDocumentId(documentId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setContent(content);
        chunk.setSourceName("rag-guide.md");
        chunk.setSectionTitle("导入说明");
        chunk.setPageNumber(1);
        return chunk;
    }
}
