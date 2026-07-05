package com.paiagent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.common.ForbiddenException;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.engine.tokenizer.ChineseTokenizer;
import com.paiagent.entity.KnowledgeBase;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.mapper.KnowledgeBaseMapper;
import com.paiagent.mapper.KnowledgeChunkMapper;
import com.paiagent.mapper.KnowledgeDocumentMapper;
import com.paiagent.mapper.KnowledgeImportTaskMapper;
import com.paiagent.service.document.DocumentParsingService;
import com.paiagent.service.rag.RagRetrievalScorer;
import com.paiagent.service.rag.RetrievalPersonalization;
import com.paiagent.service.vector.KnowledgeVectorStore;
import com.paiagent.service.vector.VectorSearchHit;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseServiceTest {

    @Test
    void retrieveExpandsAdjacentContextWindow() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        TextEmbeddingService embeddingService = mock(TextEmbeddingService.class);
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        RagRetrievalScorer scorer = scorer();

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

        KnowledgeChunk previous = chunk(1L, 1L, 10L, 0, "previous knowledge import preparation");
        KnowledgeChunk center = chunk(2L, 1L, 10L, 1, "current knowledge import guide");
        KnowledgeChunk next = chunk(3L, 1L, 10L, 2, "next retrieval debugging note");

        when(embeddingService.embed("knowledge import")).thenReturn(List.of(0.1, 0.2));
        when(embeddingService.isCompatible(any(), any(), any())).thenReturn(true);
        when(vectorStore.search(eq(1L), any(), eq(13), eq(0.0)))
                .thenReturn(List.of(new VectorSearchHit(2L, 0.91)));
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(center));
        when(chunkMapper.selectList(any()))
                .thenReturn(List.of(), List.of(previous, center, next));

        List<RetrievedChunk> chunks = service.retrieve(1L, "knowledge import", 1, 0.0, 1, 2000);

        assertEquals(1, chunks.size());
        RetrievedChunk retrieved = chunks.get(0);
        assertEquals(1, retrieved.getRank());
        assertTrue(retrieved.getKeywordScore() > 0.0);
        assertEquals(List.of(0, 1, 2), retrieved.getContextChunkIndexes());
        assertTrue(retrieved.getContextContent().contains("previous knowledge import preparation"));
        assertTrue(retrieved.getContextContent().contains("current knowledge import guide"));
        assertTrue(retrieved.getContextContent().contains("next retrieval debugging note"));
        assertTrue(retrieved.getMatchedTerms().contains("knowledge"));
    }

    @Test
    void shouldFallbackToKeywordRetrievalWhenEmbeddingProviderIsUnavailable() {
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        TextEmbeddingService embeddingService = mock(TextEmbeddingService.class);
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        KnowledgeBaseService service = new KnowledgeBaseService(
                mock(KnowledgeBaseMapper.class),
                mock(KnowledgeDocumentMapper.class),
                chunkMapper,
                mock(KnowledgeImportTaskMapper.class),
                embeddingService,
                vectorStore,
                mock(DocumentParsingService.class),
                scorer(),
                mock(ThreadPoolTaskExecutor.class)
        );

        KnowledgeChunk vpn = chunk(11L, 1L, 20L, 0, "VPN certificate reset guide");
        when(embeddingService.embed("VPN certificate")).thenThrow(new IllegalStateException("missing api key"));
        when(chunkMapper.selectList(any())).thenReturn(List.of(vpn));

        List<RetrievedChunk> chunks = service.retrieve(1L, "VPN certificate", 1, 0.0, 0, 2000);

        assertEquals(1, chunks.size());
        assertEquals(11L, chunks.get(0).getChunkId());
        assertEquals(0.0d, chunks.get(0).getVectorScore());
        assertTrue(chunks.get(0).getKeywordScore() > 0.0d);
        verify(vectorStore, never()).search(any(), any(), any(Integer.class), any(Double.class));
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

    @Test
    void retrieveAuthorizedShouldBoostChunksMatchingUserProfile() {
        KnowledgeBaseMapper knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        KnowledgeChunkMapper chunkMapper = mock(KnowledgeChunkMapper.class);
        TextEmbeddingService embeddingService = mock(TextEmbeddingService.class);
        KnowledgeVectorStore vectorStore = mock(KnowledgeVectorStore.class);
        UserRetrievalProfileService profileService = mock(UserRetrievalProfileService.class);
        KnowledgeBaseService service = new KnowledgeBaseService(
                knowledgeBaseMapper,
                mock(KnowledgeDocumentMapper.class),
                chunkMapper,
                mock(KnowledgeImportTaskMapper.class),
                embeddingService,
                vectorStore,
                mock(DocumentParsingService.class),
                scorer(),
                mock(ThreadPoolTaskExecutor.class)
        );
        ReflectionTestUtils.setField(service, "userRetrievalProfileService", profileService);

        KnowledgeChunk generic = chunk(31L, 1L, 50L, 0, "General account troubleshooting guide");
        KnowledgeChunk vpn = chunk(32L, 1L, 51L, 0, "VPN certificate reset flow handled by IT");

        when(knowledgeBaseMapper.selectById(1L)).thenReturn(knowledgeBase(1L, null, null));
        when(embeddingService.embed("VPN issue")).thenReturn(List.of(0.1, 0.2));
        when(embeddingService.isCompatible(any(), any(), any())).thenReturn(true);
        when(vectorStore.search(eq(1L), any(), eq(14), eq(0.0)))
                .thenReturn(List.of(
                        new VectorSearchHit(31L, 0.90d),
                        new VectorSearchHit(32L, 0.85d)
                ));
        when(chunkMapper.selectBatchIds(anyCollection())).thenReturn(List.of(generic, vpn));
        when(chunkMapper.selectList(any())).thenReturn(List.of());
        when(profileService.buildProfile(2L, "VPN issue"))
                .thenReturn(new RetrievalPersonalization(2L, Map.of(
                        "VPN", 20.0d,
                        "certificate", 20.0d,
                        "IT", 20.0d
                )));

        List<RetrievedChunk> chunks = service.retrieveAuthorized(1L, "VPN issue", 1, 0.0, 0, 2000, 2L, false);

        assertEquals(1, chunks.size());
        assertEquals(32L, chunks.get(0).getChunkId());
        assertEquals(1, chunks.get(0).getRank());
        assertTrue(chunks.get(0).getPersonalizationScore() > 0.0d);
        assertTrue(chunks.get(0).getPersonalizationReasons().contains("profile:VPN"));
        verify(profileService).recordInteraction(eq(2L), eq("VPN issue"), any());
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
                scorer(),
                mock(ThreadPoolTaskExecutor.class)
        );
    }

    private RagRetrievalScorer scorer() {
        return new RagRetrievalScorer(new ChineseTokenizer("a,the,and,or,to,of", 16));
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
        chunk.setSectionTitle("import guide");
        chunk.setPageNumber(1);
        return chunk;
    }
}
