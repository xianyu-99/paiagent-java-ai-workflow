package com.paiagent.engine.rerank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the Reranker module.
 * <p>
 * Prefix: {@code paiagent.rag.rerank}
 */
@Component
@ConfigurationProperties(prefix = "paiagent.rag.rerank")
public class RerankerProperties {

    /** Whether the reranker is enabled. */
    private boolean enabled = true;

    /** Whether to call an external rerank service in the online retrieval path. */
    private boolean externalEnabled = true;

    /** Whether to prefer a local HTTP Cross-Encoder reranker before cloud rerank services. */
    private boolean localEnabled = false;

    /** Local Cross-Encoder reranker endpoint, e.g. http://localhost:8001/rerank. */
    private String localBaseUrl = "http://localhost:8001/rerank";

    /** Rerank model name (DashScope: gte-rerank-v2 or qwen3-rerank). */
    private String model = "gte-rerank-v2";

    /** DashScope rerank API base URL. */
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** Request timeout in milliseconds. */
    private int timeoutMs = 2_000;

    /** Maximum retry attempts on transient failures. */
    private int maxRetries = 1;

    /** Whether LLM-based fallback reranking is enabled. */
    private boolean llmFallbackEnabled = false;

    /** Maximum number of candidates sent to the external reranker. */
    private int candidateLimit = 12;

    /** Skip model rerank when the fused top candidate is already strong enough. */
    private boolean highConfidenceSkipEnabled = true;

    /** Minimum dense vector score required to skip external rerank. */
    private double highConfidenceMinVectorScore = 0.72;

    /** Minimum sparse keyword/BM25 score required to skip external rerank. */
    private double highConfidenceMinKeywordScore = 0.08;

    /** Optional RRF score gap required to skip external rerank. 0 disables the gap check. */
    private double highConfidenceMinRrfMargin = 0.0;

    /** Whether to cache rerank results for repeated queries. */
    private boolean cacheEnabled = true;

    /** Rerank cache TTL in seconds. */
    private int cacheTtlSeconds = 300;

    /** Maximum number of cached rerank entries. */
    private int cacheMaxSize = 2048;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public boolean isExternalEnabled() { return externalEnabled; }
    public void setExternalEnabled(boolean externalEnabled) { this.externalEnabled = externalEnabled; }

    public boolean isLocalEnabled() { return localEnabled; }
    public void setLocalEnabled(boolean localEnabled) { this.localEnabled = localEnabled; }

    public String getLocalBaseUrl() { return localBaseUrl; }
    public void setLocalBaseUrl(String localBaseUrl) { this.localBaseUrl = localBaseUrl; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public boolean isLlmFallbackEnabled() { return llmFallbackEnabled; }

    public void setLlmFallbackEnabled(boolean llmFallbackEnabled) { this.llmFallbackEnabled = llmFallbackEnabled; }

    public int getCandidateLimit() { return candidateLimit; }
    public void setCandidateLimit(int candidateLimit) { this.candidateLimit = candidateLimit; }

    public boolean isHighConfidenceSkipEnabled() { return highConfidenceSkipEnabled; }
    public void setHighConfidenceSkipEnabled(boolean highConfidenceSkipEnabled) { this.highConfidenceSkipEnabled = highConfidenceSkipEnabled; }

    public double getHighConfidenceMinVectorScore() { return highConfidenceMinVectorScore; }
    public void setHighConfidenceMinVectorScore(double highConfidenceMinVectorScore) { this.highConfidenceMinVectorScore = highConfidenceMinVectorScore; }

    public double getHighConfidenceMinKeywordScore() { return highConfidenceMinKeywordScore; }
    public void setHighConfidenceMinKeywordScore(double highConfidenceMinKeywordScore) { this.highConfidenceMinKeywordScore = highConfidenceMinKeywordScore; }

    public double getHighConfidenceMinRrfMargin() { return highConfidenceMinRrfMargin; }
    public void setHighConfidenceMinRrfMargin(double highConfidenceMinRrfMargin) { this.highConfidenceMinRrfMargin = highConfidenceMinRrfMargin; }

    public boolean isCacheEnabled() { return cacheEnabled; }
    public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }

    public int getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(int cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }

    public int getCacheMaxSize() { return cacheMaxSize; }
    public void setCacheMaxSize(int cacheMaxSize) { this.cacheMaxSize = cacheMaxSize; }
}
