package com.paiagent.engine.llm.prompt;

import com.paiagent.config.PromptCacheProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptCacheServiceTest {

    @Test
    void shouldReportHitForRepeatedStablePrompt() {
        PromptCacheService cache = new PromptCacheService(testProperties(8, 10));
        String prompt = "stable system prompt for skill and tool instructions";

        PromptCacheResult first = cache.cacheStablePrompt("agent", prompt);
        PromptCacheResult second = cache.cacheStablePrompt("agent", prompt);

        assertThat(first.cacheable()).isTrue();
        assertThat(first.hit()).isFalse();
        assertThat(second.hit()).isTrue();
        assertThat(second.content()).isEqualTo(prompt);
        assertThat(second.estimatedSavedChars()).isEqualTo(prompt.length());
    }

    @Test
    void shouldSkipShortPrompts() {
        PromptCacheService cache = new PromptCacheService(testProperties(100, 10));

        PromptCacheResult result = cache.cacheStablePrompt("agent", "short");

        assertThat(result.cacheable()).isFalse();
        assertThat(result.hit()).isFalse();
        assertThat(cache.size()).isZero();
    }

    @Test
    void shouldEvictWhenMaxEntriesExceeded() {
        PromptCacheService cache = new PromptCacheService(testProperties(1, 1));

        cache.cacheStablePrompt("agent", "first prompt");
        cache.cacheStablePrompt("agent", "second prompt");
        PromptCacheResult firstAgain = cache.cacheStablePrompt("agent", "first prompt");

        assertThat(cache.size()).isEqualTo(1);
        assertThat(firstAgain.hit()).isFalse();
    }

    private PromptCacheProperties testProperties(int minimumChars, int maxEntries) {
        PromptCacheProperties properties = new PromptCacheProperties();
        properties.setEnabled(true);
        properties.setMinimumChars(minimumChars);
        properties.setMaxEntries(maxEntries);
        properties.setTtlSeconds(60);
        return properties;
    }
}
