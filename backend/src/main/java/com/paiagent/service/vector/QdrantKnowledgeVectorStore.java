package com.paiagent.service.vector;

import com.paiagent.config.RagVectorStoreProperties;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.service.TextEmbeddingService;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class QdrantKnowledgeVectorStore implements KnowledgeVectorStore {

    private final RagVectorStoreProperties properties;

    private final TextEmbeddingService textEmbeddingService;

    private final RestClient restClient;

    public QdrantKnowledgeVectorStore(RagVectorStoreProperties properties,
                                      TextEmbeddingService textEmbeddingService) {
        this.properties = properties;
        this.textEmbeddingService = textEmbeddingService;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs()));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs()));
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .baseUrl(normalizeBaseUrl(properties.getQdrantUrl()))
                .defaultHeader("api-key", properties.getQdrantApiKey() == null ? "" : properties.getQdrantApiKey())
                .build();
    }

    @Override
    public String type() {
        return "qdrant";
    }

    @Override
    public void upsert(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        ensureCollection();
        List<Map<String, Object>> points = chunks.stream()
                .filter(chunk -> chunk.getId() != null)
                .map(this::toPoint)
                .toList();
        if (points.isEmpty()) {
            return;
        }

        Map<String, Object> body = Map.of("points", points);
        restClient.put()
                .uri("/collections/{collection}/points?wait=true", collectionName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    @Override
    public List<VectorSearchHit> search(Long knowledgeBaseId, List<Double> queryEmbedding, int topK, double minScore) {
        if (knowledgeBaseId == null || queryEmbedding == null || queryEmbedding.isEmpty()) {
            return List.of();
        }

        ensureCollection();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", queryEmbedding);
        body.put("filter", filterByKnowledgeBase(knowledgeBaseId));
        body.put("limit", Math.max(1, topK));
        body.put("score_threshold", minScore);
        body.put("with_payload", true);
        body.put("with_vector", false);

        Map<?, ?> response = restClient.post()
                .uri("/collections/{collection}/points/query", collectionName())
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        return parseHits(response);
    }

    @Override
    public void deleteKnowledgeBase(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        try {
            ensureCollection();
            Map<String, Object> body = Map.of("filter", filterByKnowledgeBase(knowledgeBaseId));
            restClient.post()
                    .uri("/collections/{collection}/points/delete?wait=true", collectionName())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpStatusCodeException ignored) {
            // 删除业务库时，Qdrant 清理失败不应阻塞主数据删除。
        }
    }

    public String collectionName() {
        String prefix = StringUtils.hasText(properties.getCollectionPrefix())
                ? properties.getCollectionPrefix()
                : "paiagent_chunks";
        return sanitize(prefix);
    }

    private void ensureCollection() {
        try {
            restClient.get()
                    .uri("/collections/{collection}", collectionName())
                    .retrieve()
                    .toBodilessEntity();
            return;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() != 404) {
                throw e;
            }
        }

        Map<String, Object> vectors = new LinkedHashMap<>();
        vectors.put("size", textEmbeddingService.dimensions());
        vectors.put("distance", "Cosine");
        Map<String, Object> body = Map.of("vectors", vectors);
        restClient.put()
                .uri("/collections/{collection}", collectionName())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();
    }

    private Map<String, Object> toPoint(KnowledgeChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("knowledgeBaseId", chunk.getKnowledgeBaseId());
        payload.put("documentId", chunk.getDocumentId());
        payload.put("chunkIndex", chunk.getChunkIndex());
        payload.put("sourceName", chunk.getSourceName());
        payload.put("contentType", chunk.getContentType());
        payload.put("sectionTitle", chunk.getSectionTitle());
        payload.put("pageNumber", chunk.getPageNumber());
        payload.put("startOffset", chunk.getStartOffset());
        payload.put("endOffset", chunk.getEndOffset());
        payload.put("embeddingProvider", chunk.getEmbeddingProvider());
        payload.put("embeddingModel", chunk.getEmbeddingModel());
        payload.put("embeddingDimension", chunk.getEmbeddingDimension());

        Map<String, Object> point = new LinkedHashMap<>();
        point.put("id", chunk.getId());
        point.put("vector", textEmbeddingService.deserialize(chunk.getEmbedding()));
        point.put("payload", payload);
        return point;
    }

    private Map<String, Object> filterByKnowledgeBase(Long knowledgeBaseId) {
        return Map.of("must", List.of(
                match("knowledgeBaseId", knowledgeBaseId),
                match("embeddingProvider", textEmbeddingService.provider()),
                match("embeddingModel", textEmbeddingService.model()),
                match("embeddingDimension", textEmbeddingService.dimensions())
        ));
    }

    private Map<String, Object> match(String key, Object value) {
        return Map.of(
                "key", key,
                "match", Map.of("value", value)
        );
    }

    private List<VectorSearchHit> parseHits(Map<?, ?> response) {
        if (response == null) {
            return List.of();
        }
        Object result = response.get("result");
        Object points = result instanceof Map<?, ?> resultMap ? resultMap.get("points") : result;
        if (!(points instanceof List<?> pointList)) {
            return List.of();
        }

        List<VectorSearchHit> hits = new ArrayList<>();
        for (Object point : pointList) {
            if (!(point instanceof Map<?, ?> pointMap)) {
                continue;
            }
            Long id = toLong(pointMap.get("id"));
            double score = toDouble(pointMap.get("score"));
            if (id != null) {
                hits.add(new VectorSearchHit(id, score));
            }
        }
        return hits;
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private int timeoutMs() {
        return Math.max(1000, properties.getTimeoutMs() == null ? 30000 : properties.getTimeoutMs());
    }

    private String normalizeBaseUrl(String baseUrl) {
        String normalized = StringUtils.hasText(baseUrl) ? baseUrl.trim() : "http://localhost:6333";
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String sanitize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }
}
