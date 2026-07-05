package com.paiagent.service.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record RetrievalPersonalization(Long userId, Map<String, Double> termWeights) {

    private static final double MAX_BOOST = 0.08d;

    public boolean active() {
        return userId != null && termWeights != null && !termWeights.isEmpty();
    }

    public PersonalizationMatch match(String text) {
        if (!active() || text == null || text.isBlank()) {
            return PersonalizationMatch.empty();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        double score = 0.0d;
        List<String> reasons = new ArrayList<>();
        for (Map.Entry<String, Double> entry : termWeights.entrySet()) {
            String term = entry.getKey();
            if (term == null || term.isBlank()) {
                continue;
            }
            String normalizedTerm = term.toLowerCase(Locale.ROOT);
            if (!normalized.contains(normalizedTerm)) {
                continue;
            }
            double weight = entry.getValue() == null ? 0.0d : entry.getValue();
            score += Math.min(0.025d, Math.max(0.0d, weight) * 0.01d);
            reasons.add("profile:" + term);
            if (score >= MAX_BOOST) {
                return new PersonalizationMatch(MAX_BOOST, reasons);
            }
        }
        return new PersonalizationMatch(score, reasons);
    }

    public record PersonalizationMatch(double score, List<String> reasons) {
        static PersonalizationMatch empty() {
            return new PersonalizationMatch(0.0d, List.of());
        }
    }
}
