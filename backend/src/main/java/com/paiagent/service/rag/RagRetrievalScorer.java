package com.paiagent.service.rag;

import com.paiagent.entity.KnowledgeChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RagRetrievalScorer {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]+|[A-Za-z0-9]+");

    public List<String> searchTerms(String query) {
        List<String> terms = tokenize(query);
        return terms.stream()
                .filter(term -> term.length() >= 2)
                .limit(10)
                .toList();
    }

    public double keywordScore(String query, KnowledgeChunk chunk) {
        List<String> terms = tokenize(query);
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

        String normalizedQuery = lower(query).replaceAll("\\s+", "");
        double phraseBoost = StringUtils.hasText(normalizedQuery)
                && content.replaceAll("\\s+", "").contains(normalizedQuery)
                ? 0.20
                : 0.0;
        double contentScore = matched / (double) terms.size();
        double metadataBoost = Math.min(0.15, metadataMatched * 0.05);
        return Math.min(1.0, contentScore * 0.75 + phraseBoost + metadataBoost);
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
        Matcher matcher = TOKEN_PATTERN.matcher(query);
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
