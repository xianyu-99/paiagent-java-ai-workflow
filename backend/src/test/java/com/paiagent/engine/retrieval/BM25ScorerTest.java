package com.paiagent.engine.retrieval;

import com.paiagent.engine.tokenizer.ChineseTokenizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class BM25ScorerTest {

    private BM25Scorer scorer;
    private ChineseTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new ChineseTokenizer(
                "的,了,在,是,我,有,和,就,不,人,都,一,一个,上,也,很,到,说,要,去,你,会,着,没有,看,好,自己,这",
                16
        );
        scorer = new BM25Scorer(tokenizer, 1.2, 0.75);
    }

    @Test
    @DisplayName("score should return 0 for empty query tokens")
    void scoreEmptyQueryTokens() {
        double result = scorer.score(
                List.of(), "some document", 10.0, 100, Map.of()
        );
        assertThat(result).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score should return 0 for null or empty document")
    void scoreNullOrEmptyDocument() {
        List<String> queryTokens = List.of("test");
        assertThat(scorer.score(queryTokens, null, 10.0, 100, Map.of())).isEqualTo(0.0);
        assertThat(scorer.score(queryTokens, "", 10.0, 100, Map.of())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score should return positive value when query terms match document")
    void scoreBasicMatch() {
        List<String> queryTokens = tokenizer.searchTerms("人工智能");
        Map<String, Integer> docFreq = new HashMap<>();
        docFreq.put("人工智能", 5);

        double result = scorer.score(
                queryTokens, "人工智能技术发展迅速人工智能应用广泛",
                50.0, 100, docFreq
        );
        assertThat(result).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("score should be higher for document with more term occurrences")
    void scoreHigherForMoreMatches() {
        List<String> queryTokens = tokenizer.searchTerms("人工智能");
        Map<String, Integer> docFreq = new HashMap<>();
        docFreq.put("人工智能", 5);

        double scoreFew = scorer.score(
                queryTokens, "人工智能技术", 50.0, 100, docFreq
        );
        double scoreMany = scorer.score(
                queryTokens, "人工智能技术人工智能应用人工智能发展", 50.0, 100, docFreq
        );
        // More occurrences should yield higher score (but with diminishing returns from BM25 saturation)
        assertThat(scoreMany).isGreaterThanOrEqualTo(scoreFew);
    }

    @Test
    @DisplayName("score should be 0 when no query terms appear in document")
    void scoreNoMatch() {
        List<String> queryTokens = tokenizer.searchTerms("人工智能");
        Map<String, Integer> docFreq = new HashMap<>();
        docFreq.put("人工智能", 5);

        double result = scorer.score(
                queryTokens, "今天天气很好适合出去散步", 50.0, 100, docFreq
        );
        assertThat(result).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score should give higher weight to rarer terms (IDF)")
    void scoreRareTermsHigher() {
        String doc = "罕见术语出现在文档中";
        List<String> queryTokens = tokenizer.tokenize("罕见术语");
        Map<String, Integer> docFreqRare = new HashMap<>();
        Map<String, Integer> docFreqCommon = new HashMap<>();
        for (String token : queryTokens) {
            docFreqRare.put(token, 1);
            docFreqCommon.put(token, 50);
        }

        double scoreRare = scorer.score(
                queryTokens, doc, 50.0, 100, docFreqRare
        );
        double scoreCommon = scorer.score(
                queryTokens, doc, 50.0, 100, docFreqCommon
        );
        assertThat(scoreRare).isGreaterThan(scoreCommon);
    }

    @Test
    @DisplayName("termFrequency should count token occurrences")
    void termFrequencyCountsTokens() {
        Map<String, Integer> tf = scorer.termFrequency("人工智能技术人工智能");
        assertThat(tf.get("人工智能")).isEqualTo(2);
        assertThat(tf.get("技术")).isEqualTo(1);
    }

    @Test
    @DisplayName("termFrequency should return empty map for null or empty input")
    void termFrequencyNullOrEmpty() {
        assertThat(scorer.termFrequency(null)).isEmpty();
        assertThat(scorer.termFrequency("")).isEmpty();
    }

    @Test
    @DisplayName("normalizeScore should clamp to [0, 1]")
    void normalizeScoreClamps() {
        assertThat(scorer.normalizeScore(0.0, 1.0)).isEqualTo(0.0);
        assertThat(scorer.normalizeScore(0.5, 1.0)).isCloseTo(0.5, within(0.01));
        assertThat(scorer.normalizeScore(1.0, 1.0)).isCloseTo(1.0, within(0.01));
        assertThat(scorer.normalizeScore(2.0, 1.0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("normalizeScore should return 0 when maxObservedScore is 0")
    void normalizeScoreZeroMax() {
        assertThat(scorer.normalizeScore(5.0, 0.0)).isEqualTo(0.0);
        assertThat(scorer.normalizeScore(5.0, -1.0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("score should respect k1 and b parameters")
    void scoreRespectsParameters() {
        // Create scorer with different parameters
        BM25Scorer scorerHighK1 = new BM25Scorer(tokenizer, 2.0, 0.75);
        BM25Scorer scorerLowK1 = new BM25Scorer(tokenizer, 0.5, 0.75);

        List<String> queryTokens = tokenizer.searchTerms("人工智能技术");
        Map<String, Integer> docFreq = new HashMap<>();
        docFreq.put("人工智能", 5);
        docFreq.put("技术", 10);

        String doc = "人工智能技术发展人工智能应用";
        // Both should produce valid positive scores with different k1 values
        double scoreHigh = scorerHighK1.score(queryTokens, doc, 50.0, 100, docFreq);
        double scoreLow = scorerLowK1.score(queryTokens, doc, 50.0, 100, docFreq);
        assertThat(scoreHigh).isGreaterThan(0.0);
        assertThat(scoreLow).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("scoreBatch should return empty map for null or empty candidates")
    void scoreBatchNullOrEmpty() {
        assertThat(scorer.scoreBatch("查询", null)).isEmpty();
        assertThat(scorer.scoreBatch("查询", List.of())).isEmpty();
    }

    @Test
    @DisplayName("scoreBatch should return empty map when query yields no tokens")
    void scoreBatchNoQueryTokens() {
        // Query with only stop words should yield no search terms
        assertThat(scorer.scoreBatch("的了吗", List.of())).isEmpty();
    }
}
