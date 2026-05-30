package com.paiagent.service.rag;

import com.paiagent.entity.KnowledgeChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagRetrievalScorer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9]+");

    private static final List<String> QUERY_STOP_PHRASES = Arrays.asList(
            "请问", "帮我", "我想", "如何", "怎么", "怎样", "什么", "哪些", "为什么",
            "是否", "能否", "可以", "能够", "需要", "一下", "这个", "那个", "提示"
    );

    public List<String> searchTerms(String query) {
        List<String> terms = tokenize(query);
        return terms.stream()
                .filter(this::isSearchableTerm)
                .limit(16)
                .toList();
    }

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

    public double rerankScore(double vectorScore, double keywordScore) {
        double normalizedVectorScore = Math.max(0.0, Math.min(1.0, vectorScore));
        double normalizedKeywordScore = Math.max(0.0, Math.min(1.0, keywordScore));
        return normalizedVectorScore * 0.65 + normalizedKeywordScore * 0.35;
    }

    private List<String> tokenize(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }

        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(normalizeQuery(query));
        while (matcher.find()) {
            String token = lower(matcher.group());
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (isHanText(token)) {
                addHanNgrams(token, terms);
            } else if (token.length() >= 2) {
                terms.add(token);
            }
        }
        return new ArrayList<>(terms);
    }

    private String normalizeQuery(String query) {
        String normalized = query == null ? "" : query;
        for (String stopPhrase : QUERY_STOP_PHRASES) {
            normalized = normalized.replace(stopPhrase, " ");
        }
        return normalized;
    }

    private boolean isSearchableTerm(String term) {
        return term.length() >= 2 && !QUERY_STOP_PHRASES.contains(term);
    }

    private void addHanNgrams(String token, Set<String> terms) {
        if (token.length() <= 8) {
            terms.add(token);
        }
        for (int i = 0; i + 2 <= token.length(); i++) {
            terms.add(token.substring(i, i + 2));
        }
        if (token.length() >= 3) {
            for (int i = 0; i + 3 <= token.length(); i++) {
                terms.add(token.substring(i, i + 3));
            }
        }
    }

    private boolean isHanText(String text) {
        return text.codePoints().allMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN
        );
    }

    private String lower(String text) {
        return safe(text).toLowerCase(Locale.ROOT);
    }

    private String safe(String text) {
        return text == null ? "" : text;
    }
}
