package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.paiagent.config.RagEmbeddingProperties;
import com.paiagent.service.embedding.DashScopeEmbeddingProvider;
import com.paiagent.service.embedding.EmbeddingProvider;
import com.paiagent.service.embedding.LocalHashEmbeddingProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;

@Service
public class TextEmbeddingService {

    private final EmbeddingProvider embeddingProvider;

    public TextEmbeddingService() {
        this.embeddingProvider = new LocalHashEmbeddingProvider();
    }

    @Autowired
    public TextEmbeddingService(RagEmbeddingProperties properties) {
        this.embeddingProvider = createProvider(properties);
    }

    public List<Double> embed(String text) {
        return embeddingProvider.embed(text);
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

        // Legacy chunks created before metadata columns are compatible only with local Hash Embedding.
        return StringUtils.hasText(chunkProvider)
                || StringUtils.hasText(chunkModel)
                || chunkDimensions != null
                || "local".equalsIgnoreCase(provider());
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

    private EmbeddingProvider createProvider(RagEmbeddingProperties properties) {
        String provider = properties == null ? "local" : properties.getProvider();
        if (!StringUtils.hasText(provider)) {
            return new LocalHashEmbeddingProvider();
        }
        return switch (provider.toLowerCase(Locale.ROOT)) {
            case "dashscope", "aliyun" -> new DashScopeEmbeddingProvider(properties);
            case "local", "hash", "local-hash" -> new LocalHashEmbeddingProvider();
            default -> throw new IllegalArgumentException("Unsupported RAG embedding provider: " + provider);
        };
    }
}
