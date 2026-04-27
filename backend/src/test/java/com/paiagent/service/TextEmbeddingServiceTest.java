package com.paiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEmbeddingServiceTest {

    private final TextEmbeddingService textEmbeddingService = new TextEmbeddingService();

    @Test
    void shouldCreateFixedSizeEmbedding() {
        var embedding = textEmbeddingService.embed("Java 大模型 RAG 知识库");

        assertEquals(256, embedding.size());
        assertEquals("local", textEmbeddingService.provider());
        assertEquals("local-hash-embedding", textEmbeddingService.model());
    }

    @Test
    void shouldScoreSimilarTextHigherThanUnrelatedText() {
        var query = textEmbeddingService.embed("Java RAG 知识库");
        var similar = textEmbeddingService.embed("Java 项目实现 RAG 知识库检索");
        var unrelated = textEmbeddingService.embed("今天晚饭吃什么");

        assertTrue(textEmbeddingService.cosine(query, similar) > textEmbeddingService.cosine(query, unrelated));
    }

    @Test
    void shouldTreatLegacyChunksAsCompatibleWithLocalEmbeddingOnly() {
        assertTrue(textEmbeddingService.isCompatible(null, null, null));
    }
}
