package com.paiagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.common.ForbiddenException;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.mapper.KnowledgeImportTaskMapper;
import com.paiagent.service.document.DocumentParsingService;
import com.paiagent.service.rag.RagRetrievalScorer;
import com.paiagent.service.vector.KnowledgeVectorStore;
import com.paiagent.service.vector.VectorSearchHit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    @Test
    void retrieveExpandsAdjacentContextWindow() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        TextEmbeddingService embeddingService = mock(TextEmbeddingService.class);
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        RagRetrievalScorer scorer = new RagRetrievalScorer();

        KnowledgeBaseService service = new KnowledgeBaseService(
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeDocumentMapper.class),
                chunkMapper,
                mock(KnowledgeImportTaskMapper.class),
                embeddingService,
                vectorStore,
                mock(DocumentParsingService.class),
                scorer,
                mock(ThreadPoolTaskExecutor.class)
        );

        KnowledgeChunk previous = chunk(1L, 1L, 10L, 0, "上一段介绍知识库准备");
        KnowledgeChunk center = chunk(2L, 1L, 10L, 1, "这一段介绍知识库导入");
        KnowledgeChunk next = chunk(3L, 1L, 10L, 2, "下一段介绍检索调试");

        when(embeddingService.embed("知识库导入")).thenReturn(List.of(0.1, 0.2));
        when(embeddingService.isCompatible(any(), any(), any())).thenReturn(true);
        when(vectorStore.search(eq(1L), any(), eq(13), eq(0.0)))
                .thenReturn(List.of(new VectorSearchHit(2L, 0.91)));
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(center));
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(), List.of(previous, center, next));

        List<RetrievedChunk> chunks = service.retrieve(1L, "知识库导入", 1, 0.0, 1, 2000);

        assertEquals(1, chunks.size());
        RetrievedChunk retrieved = chunks.get(0);
        assertEquals(1, retrieved.getRank());
        assertTrue(retrieved.getKeywordScore() > 0.0);
        assertEquals(List.of(0, 1, 2), retrieved.getContextChunkIndexes());
        assertTrue(retrieved.getContextContent().contains("上一段介绍知识库准备"));
        assertTrue(retrieved.getContextContent().contains("这一段介绍知识库导入"));
        assertTrue(retrieved.getContextContent().contains("下一段介绍检索调试"));
        assertTrue(retrieved.getMatchedTerms().contains("知识库"));
    }

    @Test
    void shouldAllowSharedKnowledgeBaseForRagReadButKeepItReadOnly() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase knowledgeBase = knowledgeBase(1L, null, null);
        when(knowledgeBaseMapper.selectById(1L)).thenReturn(knowledgeBase);

        KnowledgeBaseService service = createService(knowledgeBaseMapper);

        assertEquals(knowledgeBase, service.getAuthorizedKnowledgeBase(1L, 2L, false));
        assertEquals(List.of(), service.retrieveAuthorized(1L, "", 1, 0.0, 2L, false));
        assertThrows(ForbiddenException.class, () -> service.rebuildEmbeddings(1L, 2L, false));
        assertThrows(ForbiddenException.class, () -> service.deleteKnowledgeBase(1L, 2L, false));
    }

    @Test
    void shouldKeepOwnedForeignAndDeletedKnowledgeBaseAuthorizationBoundary() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeBase owned = knowledgeBase(2L, 2L, null);
        KnowledgeBase foreign = knowledgeBase(3L, 8L, null);
        KnowledgeBase deleted = knowledgeBase(4L, null, 1);
        when(knowledgeBaseMapper.selectById(2L)).thenReturn(owned);
        when(knowledgeBaseMapper.selectById(3L)).thenReturn(foreign);
        when(knowledgeBaseMapper.selectById(4L)).thenReturn(deleted);

        KnowledgeBaseService service = createService(knowledgeBaseMapper);

        assertEquals(owned, service.getAuthorizedKnowledgeBase(2L, 2L, false));
        assertThrows(ForbiddenException.class, () -> service.getAuthorizedKnowledgeBase(3L, 2L, false));
        assertThrows(IllegalArgumentException.class, () -> service.getAuthorizedKnowledgeBase(4L, 2L, false));
        assertThrows(IllegalArgumentException.class, () -> service.getAuthorizedKnowledgeBase(5L, 2L, false));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldIncludeSharedKnowledgeBasesInRegularUserListQuery() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        when(knowledgeBaseMapper.selectList(any())).thenReturn(List.of());

        KnowledgeBaseService service = createService(knowledgeBaseMapper);
        service.listKnowledgeBases(2L, false);

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(knowledgeBaseMapper).selectList(wrapperCaptor.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), KnowledgeBase.class);
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment().toUpperCase();
        assertTrue(sqlSegment.contains("OWNER_ID"));
        assertTrue(sqlSegment.contains("IS NULL"));
    }

    private KnowledgeBaseService createService(KnowledgeBaseMapper knowledgeBaseMapper) {
        return new KnowledgeBaseService(
                knowledgeBaseMapper,
                mock(KnowledgeDocumentMapper.class),
                mock(KnowledgeChunkMapper.class),
                mock(KnowledgeImportTaskMapper.class),
                mock(TextEmbeddingService.class),
                mock(KnowledgeVectorStore.class),
                mock(DocumentParsingService.class),
                new RagRetrievalScorer(),
                mock(ThreadPoolTaskExecutor.class)
        );
    }

    private KnowledgeBase knowledgeBase(Long id, Long ownerId, Integer deleted) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setId(id);
        knowledgeBase.setOwnerId(ownerId);
        knowledgeBase.setDeleted(deleted);
        return knowledgeBase;
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
