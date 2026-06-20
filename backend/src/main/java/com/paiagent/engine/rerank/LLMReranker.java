package com.paiagent.engine.rerank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paiagent.service.embedding.EmbeddingApiKeyResolver;
import com.paiagent.service.rag.RetrievalCandidate;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-based Reranker fallback.
 * <p>
 * Calls DashScope Qwen LLM API to score relevance when the dedicated
 * Rerank API is unavailable. Uses the same API key as embedding.
 */
public class LLMReranker implements Reranker {

    private static final Logger log = LoggerFactory.getLogger(LLMReranker.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern JSON_ARRAY_PATTERN = Pattern.compile(
            "\\[\\s*((?:[\\d.]+(?:\\s*,\\s*[\\d.]+)*))\\s*]", Pattern.DOTALL);

    private static final String DEFAULT_LLM_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";

    private final RestClient restClient;
    private final EmbeddingApiKeyResolver apiKeyResolver;
    private final boolean enabled;
    private final int timeoutMs;
    private final int maxRetries;

    public LLMReranker(RerankerProperties properties) {
        this.enabled = properties.isLlufallbackEnabled();
        this.timeoutMs = properties.getTimeoutMs() > 0 ? properties.getTimeoutMs() : 15_000;
        this.maxRetries = properties.getMaxRetries() >= 0 ? properties.getMaxRetries() : 1;
        this.apiKeyResolver = new EmbeddingApiKeyResolver();
        this.restClient = RestClient.builder().build();
    }

    @Override
    public boolean isAvailable() {
        if (!enabled) return false;
        return StringUtils.hasText(apiKeyResolver.resolve());
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (!isAvailable() || candidates.isEmpty()) return candidates;

        String apiKey = apiKeyResolver.resolve();
        if (!StringUtils.hasText(apiKey)) return candidates;

        String prompt = buildRerankPrompt(query, candidates);

        for (int retry = 0; retry <= maxRetries; retry++) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", "qwen-turbo");
                body.put("messages", List.of(
                        Map.of("role", "system", "content", "You are a relevance judge. Output ONLY a JSON array of scores."),
                        Map.of("role", "user", "content", prompt)
                ));
                body.put("temperature", 0.1);
                body.put("max_tokens", 256);

                LlmResponse response = restClient.post()
                        .uri(DEFAULT_LLM_URL)
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(LlmResponse.class);

                String content = response != null && response.getChoices() != null
                        && !response.getChoices().isEmpty()
                        ? response.getChoices().get(0).getMessage().getContent() : null;

                if (!StringUtils.hasText(content)) {
                    log.warn("LLMReranker: empty LLM response");
                    return candidates;
                }

                List<Double> scores = parseScores(content, candidates.size());
                if (scores.isEmpty()) return candidates;

                int n = Math.min(scores.size(), candidates.size());
                for (int i = 0; i < n; i++) {
                    if (scores.get(i) != null && scores.get(i) > 0) {
                        candidates.get(i).rerankScore(scores.get(i));
                    }
                }
                candidates.sort(Comparator.comparing(
                        (RetrievalCandidate c) -> c.rerankScore() != null ? c.rerankScore() : 0.0).reversed());
                for (int i = 0; i < candidates.size(); i++) {
                    candidates.get(i).rank(i + 1);
                }
                return candidates;

            } catch (ResourceAccessException | RestClientResponseException e) {
                log.warn("LLMReranker: attempt {}/{} failed: {}", retry + 1, maxRetries + 1, e.getMessage());
                if (retry >= maxRetries) return candidates;
                sleepBackoff(retry);
            } catch (Exception e) {
                log.warn("LLMReranker: unexpected error: {}", e.getMessage());
                return candidates;
            }
        }
        return candidates;
    }

    private String buildRerankPrompt(String query, List<RetrievalCandidate> candidates) {
        StringBuilder sb = new StringBuilder();
        sb.append("Rate relevance of each document to the query on 0.0-1.0 scale.\n\n");
        sb.append("Query: ").append(query).append("\n\n");
        for (int i = 0; i < candidates.size(); i++) {
            String content = candidates.get(i).chunk() != null
                    && candidates.get(i).chunk().getContent() != null
                    ? candidates.get(i).chunk().getContent()
                    : candidates.get(i).contextContent();
            if (content != null && content.length() > 300) {
                content = content.substring(0, 300) + "...";
            }
            sb.append("Doc").append(i).append(": ").append(content != null ? content : "").append("\n\n");
        }
        sb.append("Return ONLY a JSON array: [score0, score1, ...]");
        return sb.toString();
    }

    private List<Double> parseScores(String response, int expectedSize) {
        try {
            String trimmed = response.trim();
            if (trimmed.startsWith("[")) {
                return objectMapper.readValue(trimmed, new TypeReference<List<Double>>() {});
            }
        } catch (Exception ignored) {}
        Matcher m = JSON_ARRAY_PATTERN.matcher(response);
        if (m.find()) {
            String captured = m.group(1);
            if (captured != null && !captured.isEmpty()) {
                List<Double> scores = new ArrayList<>();
                for (String part : captured.split("\\s*,\\s*")) {
                    part = part.trim();
                    if (!part.isEmpty()) {
                        try { scores.add(Double.parseDouble(part)); }
                        catch (NumberFormatException ignored) { scores.add(0.0); }
                    }
                }
                return scores;
            }
        }
        return List.of();
    }

    private void sleepBackoff(int retry) {
        try { Thread.sleep(Duration.ofMillis(200L * (1L << retry))); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LlmResponse {
        private List<Choice> choices;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Choice {
        private LlmMessage message;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class LlmMessage {
        private String content;
    }
}
