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

    /** Rerank model name (DashScope: gte-rerank). */
    private String model = "gte-rerank";

    /** DashScope rerank API base URL. */
    private String baseUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";

    /** Request timeout in milliseconds. */
    private int timeoutMs = 10_000;

    /** Maximum retry attempts on transient failures. */
    private int maxRetries = 2;

    /** Whether LLM-based fallback reranking is enabled. */
    private boolean llmFallbackEnabled = true;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public boolean isLlufallbackEnabled() { return llmFallbackEnabled; }
    public void setLlmFallbackEnabled(boolean llmFallbackEnabled) { this.llmFallbackEnabled = llmFallbackEnabled; }
}
