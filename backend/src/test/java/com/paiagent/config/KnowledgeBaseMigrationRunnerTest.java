package com.paiagent.config;

import com.paiagent.service.TextEmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseMigrationRunnerTest {

    @Test
    void shouldRunKnowledgeBaseMigrationBeforeGraphBackfill() {
        Order migrationOrder = KnowledgeBaseMigrationRunner.class.getAnnotation(Order.class);
        Order backfillOrder = KnowledgeGraphBackfillRunner.class.getAnnotation(Order.class);

        assertEquals(0, migrationOrder.value());
        assertEquals(100, backfillOrder.value());
    }

    @Test
    void shouldEnsureFulltextIndexWhenMigratingExistingKnowledgeChunkTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(), any())).thenReturn(1);

        KnowledgeBaseMigrationRunner runner = new KnowledgeBaseMigrationRunner(
                jdbcTemplate,
                mock(TextEmbeddingService.class)
        );

        ReflectionTestUtils.invokeMethod(runner, "migrateKnowledgeChunkSourceMetadata");

        verify(jdbcTemplate).queryForObject(anyString(), eq(Integer.class), eq("knowledge_chunk"), eq("idx_chunk_fulltext"));
    }

    @Test
    void shouldNotCallEmbeddingProviderWhenSeedDocumentAndChunkAlreadyExist() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TextEmbeddingService embeddingService = mock(TextEmbeddingService.class);
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq(3L), eq("seed-hash"))).thenReturn(9L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(3L), eq(9L))).thenReturn(1);

        KnowledgeBaseMigrationRunner runner = new KnowledgeBaseMigrationRunner(jdbcTemplate, embeddingService);

        ReflectionTestUtils.invokeMethod(
                runner,
                "seedKnowledgeDocument",
                3L,
                "VPN SOP.md",
                "seed-hash",
                "VPN SOP",
                "content"
        );

        verify(embeddingService, never()).embed(anyString());
    }
}
