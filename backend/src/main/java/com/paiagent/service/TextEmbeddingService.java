package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.paiagent.config.RagEmbeddingProperties;
import com.paiagent.service.embedding.DashScopeEmbeddingProvider;
import com.paiagent.service.embedding.EmbeddingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class TextEmbeddingService {

    private final EmbeddingProvider embeddingProvider;

    private final RagEmbeddingProperties properties;

    private final QueryEmbeddingCache queryEmbeddingCache;

    @Autowired
    public TextEmbeddingService(RagEmbeddingProperties properties) {
        this(createProvider(properties), properties);
    }

    TextEmbeddingService(EmbeddingProvider embeddingProvider, RagEmbeddingProperties properties) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.properties = properties == null ? new RagEmbeddingProperties() : properties;
        this.queryEmbeddingCache = new QueryEmbeddingCache(
                safeCacheTtl(this.properties),
                Math.max(0, this.properties.getCacheMaxSize() == null ? 2048 : this.properties.getCacheMaxSize())
        );
    }

    public List<Double> embed(String text) {
        String normalizedText = normalizeCacheText(text);
        if (!Boolean.TRUE.equals(properties.getCacheEnabled()) || queryEmbeddingCache.disabled()) {
            return embeddingProvider.embed(normalizedText);
        }
        String key = String.join(":",
                embeddingProvider.provider(),
                embeddingProvider.model(),
                String.valueOf(embeddingProvider.dimensions()),
                sha256(normalizedText)
        );
        return queryEmbeddingCache.get(key, () -> embeddingProvider.embed(normalizedText));
    }

    public List<List<Double>> embedBatch(List<String> texts) {
        return embeddingProvider.embedBatch(texts);
    }

    public double cosine(List<Double> left, List<Double> right) {
        return embeddingProvider.cosine(left, right);
    }

    public String provider() {
        return embeddingProvider.provider();
    }

    public String model() {
        return embeddingProvider.model();
    }

    public int dimensions() {
        return embeddingProvider.dimensions();
    }

    public String serialize(List<Double> embedding) {
        return JSON.toJSONString(embedding);
    }

    public List<Double> deserialize(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        return JSON.parseArray(embeddingJson, Double.class);
    }

    public boolean isCompatible(String chunkProvider, String chunkModel, Integer chunkDimensions) {
        if (chunkDimensions != null && chunkDimensions != dimensions()) {
            return false;
        }
        if (StringUtils.hasText(chunkProvider) && !provider().equalsIgnoreCase(chunkProvider)) {
            return false;
        }
        if (StringUtils.hasText(chunkModel) && !model().equalsIgnoreCase(chunkModel)) {
            return false;
        }

        return StringUtils.hasText(chunkProvider)
                || StringUtils.hasText(chunkModel)
                || chunkDimensions != null;
    }

    public String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算文本摘要失败", e);
        }
    }

    private static EmbeddingProvider createProvider(RagEmbeddingProperties properties) {
        if (properties == null || !StringUtils.hasText(properties.getProvider())) {
            throw new IllegalStateException(
                    "RAG embedding provider is not configured. " +
                    "Set 'paiagent.rag.embedding.provider' (e.g., 'dashscope') and the corresponding API key.");
        }
        String provider = properties.getProvider().toLowerCase(Locale.ROOT);
        return switch (provider) {
            case "dashscope", "aliyun" -> new DashScopeEmbeddingProvider(properties);
            default -> throw new IllegalArgumentException("Unsupported RAG embedding provider: " + provider);
        };
    }

    private static Duration safeCacheTtl(RagEmbeddingProperties properties) {
        int seconds = properties.getCacheTtlSeconds() == null ? 3600 : properties.getCacheTtlSeconds();
        return Duration.ofSeconds(Math.max(0, seconds));
    }

    private static String normalizeCacheText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    private static class QueryEmbeddingCache {
        private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
        private final Map<String, Object> locks = new ConcurrentHashMap<>();
        private final Duration ttl;
        private final int maxSize;

        private QueryEmbeddingCache(Duration ttl, int maxSize) {
            this.ttl = ttl;
            this.maxSize = maxSize;
        }

        private boolean disabled() {
            return maxSize <= 0 || ttl.isZero() || ttl.isNegative();
        }

        private List<Double> get(String key, Supplier<List<Double>> loader) {
            long now = System.currentTimeMillis();
            CacheEntry existing = entries.get(key);
            if (existing != null && !existing.expired(now, ttl)) {
                return existing.copy();
            }

            Object lock = locks.computeIfAbsent(key, ignored -> new Object());
            synchronized (lock) {
                try {
                    long lockedNow = System.currentTimeMillis();
                    CacheEntry rechecked = entries.get(key);
                    if (rechecked != null && !rechecked.expired(lockedNow, ttl)) {
                        return rechecked.copy();
                    }
                    List<Double> loaded = loader.get();
                    CacheEntry stored = new CacheEntry(List.copyOf(loaded == null ? List.of() : loaded), lockedNow);
                    entries.put(key, stored);
                    prune(lockedNow);
                    return stored.copy();
                } finally {
                    locks.remove(key, lock);
                }
            }
        }

        private void prune(long now) {
            if (entries.size() <= maxSize) {
                return;
            }
            entries.entrySet().removeIf(entry -> entry.getValue().expired(now, ttl));
            while (entries.size() > maxSize) {
                String oldestKey = null;
                long oldestCreatedAt = Long.MAX_VALUE;
                for (Map.Entry<String, CacheEntry> entry : entries.entrySet()) {
                    if (entry.getValue().createdAtMs() < oldestCreatedAt) {
                        oldestKey = entry.getKey();
                        oldestCreatedAt = entry.getValue().createdAtMs();
                    }
                }
                if (oldestKey == null) {
                    return;
                }
                entries.remove(oldestKey);
            }
        }
    }

    private record CacheEntry(List<Double> embedding, long createdAtMs) {
        private boolean expired(long nowMs, Duration ttl) {
            return nowMs - createdAtMs >= ttl.toMillis();
        }

        private List<Double> copy() {
            return new ArrayList<>(embedding);
        }
    }
}
