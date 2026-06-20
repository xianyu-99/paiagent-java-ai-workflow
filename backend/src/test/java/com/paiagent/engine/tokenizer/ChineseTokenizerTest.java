package com.paiagent.engine.tokenizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChineseTokenizerTest {

    private ChineseTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        // Use the constructor directly with known stop words for deterministic tests
        tokenizer = new ChineseTokenizer(
                "的,了,在,是,我,有,和,就,不,人,都,一,一个,上,也,很,到,说,要,去,你,会,着,没有,看,好,自己,这",
                16
        );
    }

    @Test
    @DisplayName("tokenize should segment Chinese text")
    void tokenizeChineseText() {
        List<String> tokens = tokenizer.tokenize("人工智能技术发展迅速");
        assertThat(tokens).isNotEmpty();
        assertThat(tokens).contains("人工智能", "技术", "发展");
    }

    @Test
    @DisplayName("tokenize should handle mixed Chinese-English text")
    void tokenizeMixedText() {
        List<String> tokens = tokenizer.tokenize("AI人工智能Python编程");
        assertThat(tokens).isNotEmpty();
        assertThat(tokens.stream().anyMatch(t -> t.equalsIgnoreCase("ai"))).isTrue();
        assertThat(tokens.stream().anyMatch(t -> t.equalsIgnoreCase("python"))).isTrue();
    }

    @Test
    @DisplayName("tokenize should return empty list for null input")
    void tokenizeNull() {
        assertThat(tokenizer.tokenize(null)).isEmpty();
    }

    @Test
    @DisplayName("tokenize should return empty list for empty string")
    void tokenizeEmpty() {
        assertThat(tokenizer.tokenize("")).isEmpty();
    }

    @Test
    @DisplayName("tokenize should return empty list for blank string")
    void tokenizeBlank() {
        assertThat(tokenizer.tokenize("   ")).isEmpty();
    }

    @Test
    @DisplayName("searchTerms should filter stop words")
    void searchTermsFiltersStopWords() {
        List<String> terms = tokenizer.searchTerms("我是一个人工智能助手");
        // "我" and "是" and "一个" are stop words, should be filtered
        assertThat(terms).doesNotContain("我", "是", "一个");
        // Meaningful terms should remain
        assertThat(terms).contains("人工智能", "助手");
    }

    @Test
    @DisplayName("searchTerms should deduplicate tokens")
    void searchTermsDeduplicates() {
        List<String> terms = tokenizer.searchTerms("人工智能人工智能技术");
        long count = terms.stream().filter(t -> t.equals("人工智能")).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("searchTerms should respect maxSearchTerms limit")
    void searchTermsRespectsLimit() {
        // Create a tokenizer with a small limit
        ChineseTokenizer limited = new ChineseTokenizer(
                "的,了,在,是,我,有,和,就,不,人,都,一,一个,上,也,很,到,说,要,去,你,会,着,没有,看,好,自己,这",
                3
        );
        List<String> terms = limited.searchTerms("自然语言处理是人工智能的一个重要分支涉及机器学习深度学习");
        assertThat(terms).hasSizeLessThanOrEqualTo(3);
    }

    @Test
    @DisplayName("searchTerms should return empty list for null or empty query")
    void searchTermsNullOrEmpty() {
        assertThat(tokenizer.searchTerms(null)).isEmpty();
        assertThat(tokenizer.searchTerms("")).isEmpty();
    }

    @Test
    @DisplayName("searchTerms should skip single-character tokens")
    void searchTermsSkipsSingleChars() {
        List<String> terms = tokenizer.searchTerms("我有一本书");
        // Single chars should be filtered; meaningful multi-char tokens kept
        for (String term : terms) {
            assertThat(term.length()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("tokenize should handle English-only text")
    void tokenizeEnglishOnly() {
        List<String> tokens = tokenizer.tokenize("machine learning is great");
        assertThat(tokens).isNotEmpty();
        assertThat(tokens).contains("machine", "learning");
    }

    @Test
    @DisplayName("searchTerms should preserve order of first occurrence")
    void searchTermsPreservesOrder() {
        List<String> terms = tokenizer.searchTerms("深度学习机器学习自然语言处理");
        assertThat(terms).isNotEmpty();
        // Terms should appear in order of first occurrence
        if (terms.size() >= 2) {
            int idxDL = terms.indexOf("深度学习");
            int idxML = terms.indexOf("机器学习");
            if (idxDL >= 0 && idxML >= 0) {
                assertThat(idxDL).isLessThan(idxML);
            }
        }
    }
}
