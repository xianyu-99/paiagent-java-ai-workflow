package com.paiagent.service.rag;

import com.paiagent.engine.tokenizer.ChineseTokenizer;
import com.paiagent.entity.KnowledgeChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Component
public class RagRetrievalScorer {

    private static final List<String> QUERY_STOP_PHRASES = Arrays.asList(
            "请问", "帮我", "我想", "如何", "怎么", "怎样", "什么", "哪些", "为什么",
            "是否", "能否", "可以", "能够", "需要", "一下", "这个", "那个", "提示"
    );

    private final ChineseTokenizer tokenizer;

    public RagRetrievalScorer(ChineseTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /**
     * Extract search terms from a query: normalize (remove query fillers),
     * then tokenize with jieba and filter stop words.
     */
    public List<String> searchTerms(String query) {
        String normalized = normalizeQuery(query);
        return tokenizer.searchTerms(normalized);
    }

    /**
     * Compute a keyword match score for a chunk against a query.
     * Uses jieba-tokenized terms and checks for presence in content and metadata.
     */
    public double keywordScore(String query, KnowledgeChunk chunk) {
        List<String> terms = searchTerms(query);
        if (terms.isEmpty() || chunk == null) {
            return 0.0;
        }

        String content = lower(chunk.getContent());
        String metadata = lower(String.join(" ",
                safe(chunk.getSourceName()),
                safe(chunk.getSectionTitle())
        ));
        int matched = 0;
        int metadataMatched = 0;
        for (String term : terms) {
            if (content.contains(term)) {
                matched++;
            }
            if (metadata.contains(term)) {
                metadataMatched++;
            }
        }

        String normalizedQuery = lower(normalizeQuery(query)).replaceAll("\\s+", "");
        double phraseBoost = StringUtils.hasText(normalizedQuery)
                && content.replaceAll("\\s+", "").contains(normalizedQuery)
                ? 0.20
                : 0.0;
        double metadataPhraseBoost = StringUtils.hasText(normalizedQuery)
                && metadata.replaceAll("\\s+", "").contains(normalizedQuery)
                ? 0.10
                : 0.0;
        double contentScore = matched / (double) terms.size();
        double metadataBoost = Math.min(0.15, metadataMatched * 0.05);
        return Math.min(1.0, contentScore * 0.70 + phraseBoost + metadataBoost + metadataPhraseBoost);
    }

    /**
     * Return which search terms matched in the chunk (content + metadata).
     */
    public List<String> matchedTerms(String query, KnowledgeChunk chunk) {
        if (chunk == null) {
            return List.of();
        }
        String searchable = lower(String.join(" ",
                safe(chunk.getContent()),
                safe(chunk.getSourceName()),
                safe(chunk.getSectionTitle())
        ));
        return searchTerms(query).stream()
                .filter(searchable::contains)
                .limit(12)
                .toList();
    }

    /**
     * Combine vector and keyword scores into a single rerank score.
     * Weight: 65% vector, 35% keyword.
     */
    public double rerankScore(double vectorScore, double keywordScore) {
        double normalizedVectorScore = Math.max(0.0, Math.min(1.0, vectorScore));
        double normalizedKeywordScore = Math.max(0.0, Math.min(1.0, keywordScore));
        return normalizedVectorScore * 0.65 + normalizedKeywordScore * 0.35;
    }

    /**
     * Normalize a query by removing common Chinese question filler phrases.
     */
    private String normalizeQuery(String query) {
        String normalized = query == null ? "" : query;
        for (String stopPhrase : QUERY_STOP_PHRASES) {
            normalized = normalized.replace(stopPhrase, " ");
        }
        return normalized;
    }

    private String lower(String text) {
        return safe(text).toLowerCase(Locale.ROOT);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
