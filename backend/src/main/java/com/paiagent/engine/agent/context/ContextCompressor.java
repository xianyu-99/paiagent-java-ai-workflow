package com.paiagent.engine.agent.context;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class ContextCompressor {

    private static final int MIN_BUDGET = 240;
    private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s,.;:!?，。；：！？、()（）\\[\\]【】{}<>《》\"']+");

    public ContextCompressionResult compress(String context, String query, int maxChars) {
        if (!StringUtils.hasText(context)) {
            return new ContextCompressionResult("", 0, 0, 0, 0, 0, 1.0, false);
        }

        int budget = Math.max(MIN_BUDGET, maxChars);
        String normalizedContext = context.trim();
        List<String> lines = normalizedContext.lines()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();

        if (normalizedContext.length() <= budget) {
            return new ContextCompressionResult(
                    normalizedContext,
                    normalizedContext.length(),
                    normalizedContext.length(),
                    lines.size(),
                    lines.size(),
                    0,
                    1.0,
                    false
            );
        }

        Set<String> queryTerms = extractQueryTerms(query);
        List<ScoredLine> scoredLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            scoredLines.add(new ScoredLine(i, line, score(line, queryTerms)));
        }

        List<ScoredLine> selected = scoredLines.stream()
                .sorted(Comparator
                        .comparingInt(ScoredLine::score).reversed()
                        .thenComparingInt(ScoredLine::index))
                .toList();

        List<ScoredLine> kept = new ArrayList<>();
        int bodyBudget = Math.max(MIN_BUDGET / 2, budget - 140);
        int used = 0;
        for (ScoredLine line : selected) {
            int nextLength = line.text().length() + 1;
            if (used + nextLength > bodyBudget) {
                continue;
            }
            kept.add(line);
            used += nextLength;
        }

        kept.sort(Comparator.comparingInt(ScoredLine::index));
        String compressedBody = String.join("\n", kept.stream().map(ScoredLine::text).toList());
        int dropped = Math.max(0, lines.size() - kept.size());
        String note = "\n\n[ContextCompression] originalChars=" + normalizedContext.length()
                + ", compressedChars=" + compressedBody.length()
                + ", keptLines=" + kept.size()
                + ", droppedLines=" + dropped;

        String compressed = trimToBudget(compressedBody + note, budget);
        double ratio = normalizedContext.isEmpty()
                ? 1.0
                : (double) compressed.length() / (double) normalizedContext.length();

        return new ContextCompressionResult(
                compressed,
                normalizedContext.length(),
                compressed.length(),
                lines.size(),
                kept.size(),
                dropped,
                ratio,
                true
        );
    }

    private int score(String line, Set<String> queryTerms) {
        String lower = line.toLowerCase(Locale.ROOT);
        int score = 0;

        if (line.startsWith("===") || line.startsWith("[") || line.startsWith("#")) {
            score += 8;
        }
        if (containsAny(lower, "citation", "source", "graph", "evidence", "引用", "来源")) {
            score += 8;
        }
        if (containsAny(lower, "final answer", "observation", "action", "error", "failed", "timeout")) {
            score += 6;
        }
        if (containsAny(lower, "工单", "审批", "报销", "请假", "vpn", "sla", "人工", "升级")) {
            score += 5;
        }
        for (String term : queryTerms) {
            if (lower.contains(term)) {
                score += 4;
            }
        }
        if (line.length() <= 160) {
            score += 1;
        }
        return score;
    }

    private Set<String> extractQueryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (!StringUtils.hasText(query)) {
            return terms;
        }

        String normalized = query.toLowerCase(Locale.ROOT);
        for (String token : SPLIT_PATTERN.split(normalized)) {
            if (token.length() >= 2) {
                terms.add(token);
            }
        }

        if (terms.isEmpty() && normalized.length() >= 2) {
            int limit = Math.min(normalized.length(), 32);
            for (int i = 0; i + 2 <= limit; i += 2) {
                terms.add(normalized.substring(i, i + 2));
            }
        }
        return terms;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String trimToBudget(String text, int budget) {
        if (text.length() <= budget) {
            return text;
        }
        return text.substring(0, Math.max(0, budget - 18)) + "\n[trimmed]";
    }

    private record ScoredLine(int index, String text, int score) {
    }
}
