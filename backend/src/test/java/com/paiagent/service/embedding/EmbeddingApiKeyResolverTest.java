package com.paiagent.service.embedding;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingApiKeyResolverTest {

    @Test
    void shouldPreferExplicitRagEmbeddingApiKey() {
        String resolved = EmbeddingApiKeyResolver.resolve(
                "rag-key",
                Map.of("Qwen_API_KEY", "qwen-key")
        );

        assertEquals("rag-key", resolved);
    }

    @Test
    void shouldFallbackToMixedCaseQwenApiKey() {
        String resolved = EmbeddingApiKeyResolver.resolve(
                "",
                Map.of("Qwen_API_KEY", "qwen-key")
        );

        assertEquals("qwen-key", resolved);
    }

    @Test
    void shouldFallbackToUpperCaseQwenApiKey() {
        String resolved = EmbeddingApiKeyResolver.resolve(
                null,
                Map.of("QWEN_API_KEY", "upper-qwen-key")
        );

        assertEquals("upper-qwen-key", resolved);
    }

    @Test
    void shouldFallbackToLegacyApiKey() {
        String resolved = EmbeddingApiKeyResolver.resolve(
                null,
                Map.of("API_KEY", "dashscope-key")
        );

        assertEquals("dashscope-key", resolved);
    }

    @Test
    void shouldIgnorePlaceholderKeysAndUseNextCandidate() {
        String resolved = EmbeddingApiKeyResolver.resolve(
                "sk-placeholder",
                Map.of(
                        "Qwen_API_KEY", "sk-placeholder",
                        "QWEN_API_KEY", "real-qwen-key"
                )
        );

        assertEquals("real-qwen-key", resolved);
    }
}
