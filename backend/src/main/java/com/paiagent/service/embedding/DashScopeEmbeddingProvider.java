package com.paiagent.service.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.paiagent.common.VectorMath;
import com.paiagent.config.RagEmbeddingProperties;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class DashScopeEmbeddingProvider implements EmbeddingProvider, AutoCloseable {

    private final RagEmbeddingProperties properties;

    private final RestClient restClient;

    private final int dimensions;

    private final EmbeddingRequestLimiter requestLimiter;

    private final SingleTextBatcher singleTextBatcher;

    public DashScopeEmbeddingProvider(RagEmbeddingProperties properties) {
        this.properties = properties;
        this.dimensions = Math.max(1, properties.getDimensions() == null ? 1024 : properties.getDimensions());
        this.requestLimiter = new EmbeddingRequestLimiter(maxConcurrentRequests(), rateLimitPermitsPerSecond());
        this.singleTextBatcher = requestBatchingEnabled() ? new SingleTextBatcher() : null;
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
        if (singleTextBatcher != null) {
            return singleTextBatcher.embed(text == null ? "" : text);
        }
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
        int batchSize = batchSize();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            result.addAll(requestBatch(texts.subList(start, end), apiKey));
        }
        return result;
    }

    @Override
    public double cosine(List<Double> left, List<Double> right) {
        return VectorMath.cosine(left, right);
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

    @Override
    public void close() {
        if (singleTextBatcher != null) {
            singleTextBatcher.close();
        }
    }

    private List<List<Double>> requestBatch(List<String> texts, String apiKey) {
        return executeWithRetry(() -> requestOnce(texts, apiKey));
    }

    private List<List<Double>> requestOnce(List<String> texts, String apiKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        body.put("input", texts.stream().map(text -> StringUtils.hasText(text) ? text : " ").toList());
        body.put("dimensions", dimensions);

        EmbeddingResponse response = requestLimiter.execute(() -> restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey)
                .body(body)
                .retrieve()
                .body(EmbeddingResponse.class));

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
        return EmbeddingApiKeyResolver.resolve(properties.getApiKey());
    }

    private int timeoutMs() {
        return Math.max(1000, properties.getTimeoutMs() == null ? 30000 : properties.getTimeoutMs());
    }

    private int batchSize() {
        return Math.max(1, properties.getBatchSize() == null ? 16 : properties.getBatchSize());
    }

    private boolean requestBatchingEnabled() {
        return Boolean.TRUE.equals(properties.getRequestBatchingEnabled()) && batchSize() > 1;
    }

    private int requestBatchWindowMs() {
        return Math.max(0, properties.getRequestBatchWindowMs() == null
                ? 20
                : properties.getRequestBatchWindowMs());
    }

    private int maxConcurrentRequests() {
        return Math.max(1, properties.getMaxConcurrentRequests() == null ? 4 : properties.getMaxConcurrentRequests());
    }

    private double rateLimitPermitsPerSecond() {
        return Math.max(0.0, properties.getRateLimitPermitsPerSecond() == null
                ? 4.0
                : properties.getRateLimitPermitsPerSecond());
    }

    private int retryMaxAttempts() {
        return Math.max(1, properties.getRetryMaxAttempts() == null ? 3 : properties.getRetryMaxAttempts());
    }

    private long retryInitialBackoffMs() {
        return Math.max(0, properties.getRetryInitialBackoffMs() == null ? 300 : properties.getRetryInitialBackoffMs());
    }

    private long retryMaxBackoffMs() {
        return Math.max(retryInitialBackoffMs(), properties.getRetryMaxBackoffMs() == null
                ? 2000
                : properties.getRetryMaxBackoffMs());
    }

    private List<List<Double>> executeWithRetry(Supplier<List<List<Double>>> supplier) {
        int maxAttempts = retryMaxAttempts();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                last = e;
                if (attempt >= maxAttempts || !isRetryable(e)) {
                    throw e;
                }
                sleep(backoffMs(attempt));
            }
        }
        throw last == null ? new IllegalStateException("DashScope Embedding request failed") : last;
    }

    private boolean isRetryable(RuntimeException e) {
        if (e instanceof ResourceAccessException) {
            return true;
        }
        if (e instanceof RestClientResponseException responseException) {
            int status = responseException.getStatusCode().value();
            return status == 429 || status == 408 || status >= 500;
        }
        return false;
    }

    private long backoffMs(int attempt) {
        long initial = retryInitialBackoffMs();
        if (initial <= 0) {
            return 0;
        }
        long delay = initial;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(delay * 2, retryMaxBackoffMs());
        }
        return delay;
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying DashScope Embedding request", e);
        }
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

    private long batchAwaitTimeoutMs() {
        long retryBudget = (long) retryMaxAttempts() * (timeoutMs() + retryMaxBackoffMs());
        return Math.max(timeoutMs(), retryBudget + 5_000L);
    }

    private ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private class SingleTextBatcher {
        private final Object lock = new Object();
        private final List<PendingEmbedding> pending = new ArrayList<>();
        private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                daemonThreadFactory("rag-embedding-batch-scheduler"));
        private final ExecutorService workerPool = Executors.newFixedThreadPool(
                maxConcurrentRequests(),
                daemonThreadFactory("rag-embedding-batch-worker"));
        private ScheduledFuture<?> scheduledFlush;

        private List<Double> embed(String text) {
            CompletableFuture<List<Double>> future = new CompletableFuture<>();
            boolean flushNow = false;
            synchronized (lock) {
                pending.add(new PendingEmbedding(text, future));
                if (pending.size() >= batchSize()) {
                    cancelScheduledFlush();
                    flushNow = true;
                } else if (scheduledFlush == null || scheduledFlush.isDone()) {
                    scheduledFlush = scheduler.schedule(this::dispatch, requestBatchWindowMs(), TimeUnit.MILLISECONDS);
                }
            }

            if (flushNow) {
                dispatch();
            }

            try {
                return future.get(batchAwaitTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for batched embedding result", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("Batched embedding request failed", cause);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IllegalStateException("Timed out while waiting for batched embedding result", e);
            }
        }

        private void dispatch() {
            List<PendingEmbedding> batch = drainBatch();
            if (batch.isEmpty()) {
                return;
            }
            workerPool.execute(() -> completeBatch(batch));
        }

        private List<PendingEmbedding> drainBatch() {
            synchronized (lock) {
                if (pending.isEmpty()) {
                    scheduledFlush = null;
                    return List.of();
                }
                int limit = Math.min(batchSize(), pending.size());
                List<PendingEmbedding> batch = new ArrayList<>(pending.subList(0, limit));
                pending.subList(0, limit).clear();
                scheduledFlush = null;
                if (!pending.isEmpty()) {
                    scheduledFlush = scheduler.schedule(this::dispatch, requestBatchWindowMs(), TimeUnit.MILLISECONDS);
                }
                return batch;
            }
        }

        private void completeBatch(List<PendingEmbedding> batch) {
            try {
                String apiKey = resolveApiKey();
                List<String> texts = batch.stream().map(PendingEmbedding::text).toList();
                List<List<Double>> embeddings = requestBatch(texts, apiKey);
                if (embeddings.size() != batch.size()) {
                    throw new IllegalStateException("Batched embedding result size does not match request size");
                }
                for (int i = 0; i < batch.size(); i++) {
                    batch.get(i).future().complete(embeddings.get(i));
                }
            } catch (RuntimeException e) {
                for (PendingEmbedding request : batch) {
                    request.future().completeExceptionally(e);
                }
            }
        }

        private void cancelScheduledFlush() {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
        }

        private void close() {
            scheduler.shutdownNow();
            workerPool.shutdownNow();
        }
    }

    private record PendingEmbedding(String text, CompletableFuture<List<Double>> future) {
    }

    private static class EmbeddingRequestLimiter {
        private final Semaphore semaphore;
        private final TokenBucket tokenBucket;

        private EmbeddingRequestLimiter(int maxConcurrentRequests, double permitsPerSecond) {
            this.semaphore = new Semaphore(Math.max(1, maxConcurrentRequests), true);
            this.tokenBucket = permitsPerSecond <= 0 ? null : new TokenBucket(permitsPerSecond);
        }

        private <T> T execute(Supplier<T> supplier) {
            if (tokenBucket != null) {
                tokenBucket.acquire();
            }
            acquire();
            try {
                return supplier.get();
            } finally {
                semaphore.release();
            }
        }

        private void acquire() {
            try {
                semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for embedding request slot", e);
            }
        }
    }

    private static class TokenBucket {
        private final double permitsPerSecond;
        private final double capacity;
        private double tokens;
        private long lastRefillNanos;

        private TokenBucket(double permitsPerSecond) {
            this.permitsPerSecond = permitsPerSecond;
            this.capacity = Math.max(1.0, permitsPerSecond);
            this.tokens = capacity;
            this.lastRefillNanos = System.nanoTime();
        }

        private synchronized void acquire() {
            while (true) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                long waitNanos = Math.max(1L, (long) (((1.0 - tokens) / permitsPerSecond) * 1_000_000_000L));
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, waitNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for embedding rate limit token", e);
                }
            }
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSeconds <= 0) {
                return;
            }
            tokens = Math.min(capacity, tokens + elapsedSeconds * permitsPerSecond);
            lastRefillNanos = now;
        }
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
