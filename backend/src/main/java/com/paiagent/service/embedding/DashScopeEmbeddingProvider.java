package com.paiagent.service.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paiagent.config.RagEmbeddingProperties;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DashScopeEmbeddingProvider implements EmbeddingProvider {

    private final RagEmbeddingProperties properties;

    private final RestClient restClient;

    private final int dimensions;

    public DashScopeEmbeddingProvider(RagEmbeddingProperties properties) {
        this.properties = properties;
        this.dimensions = Math.max(1, properties.getDimensions() == null ? 1024 : properties.getDimensions());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs()));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(resolveEndpoint(properties.getBaseUrl()))
                .build();
    }

    @Override
    public List<Double> embed(String text) {
        List<List<Double>> embeddings = embedBatch(List.of(text == null ? "" : text));
        return embeddings.isEmpty() ? zeroVector() : embeddings.get(0);
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        String apiKey = resolveApiKey();
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("RAG Embedding 已切换为 dashscope，但缺少 RAG_EMBEDDING_API_KEY 或 API_KEY");
        }

        List<List<Double>> result = new ArrayList<>(texts.size());
        int batchSize = Math.max(1, properties.getBatchSize() == null ? 16 : properties.getBatchSize());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            result.addAll(requestBatch(texts.subList(start, end), apiKey));
        }
        return result;
    }

    @Override
    public double cosine(List<Double> left, List<Double> right) {
        return new LocalHashEmbeddingProvider().cosine(left, right);
    }

    @Override
    public String provider() {
        return "dashscope";
    }

    @Override
    public String model() {
        return StringUtils.hasText(properties.getModel()) ? properties.getModel() : "text-embedding-v4";
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private List<List<Double>> requestBatch(List<String> texts, String apiKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("input", texts.stream().map(text -> StringUtils.hasText(text) ? text : " ").toList());
        body.put("dimensions", dimensions);

        EmbeddingResponse response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(EmbeddingResponse.class);

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new IllegalStateException("DashScope Embedding 接口未返回向量数据");
        }

        List<List<Double>> embeddings = response.getData().stream()
                .sorted(Comparator.comparing(data -> data.getIndex() == null ? 0 : data.getIndex()))
                .map(EmbeddingData::getEmbedding)
                .toList();
        if (embeddings.size() != texts.size()) {
            throw new IllegalStateException("DashScope Embedding 返回数量与请求文本数量不一致");
        }
        return embeddings;
    }

    private String resolveApiKey() {
        if (StringUtils.hasText(properties.getApiKey())) {
            return properties.getApiKey();
        }
        return System.getenv("API_KEY");
    }

    private int timeoutMs() {
        return Math.max(1000, properties.getTimeoutMs() == null ? 30000 : properties.getTimeoutMs());
    }

    private String resolveEndpoint(String baseUrl) {
        String normalized = StringUtils.hasText(baseUrl)
                ? baseUrl.trim()
                : "https://dashscope.aliyuncs.com/compatible-mode/v1";
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/embeddings")) {
            return normalized;
        }
        return normalized + "/embeddings";
    }

    private List<Double> zeroVector() {
        List<Double> vector = new ArrayList<>(dimensions);
        for (int i = 0; i < dimensions; i++) {
            vector.add(0.0);
        }
        return vector;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        private List<EmbeddingData> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingData {
        private Integer index;
        private List<Double> embedding;
    }
}
