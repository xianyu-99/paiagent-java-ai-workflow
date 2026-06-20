package com.paiagent.service.embedding;

import com.paiagent.config.RagEmbeddingProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashScopeEmbeddingProviderTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldRetry429AndReturnEmbedding() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                write(exchange, 429, "{\"error\":\"limit_requests\"}");
                return;
            }
            write(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        });

        var provider = new DashScopeEmbeddingProvider(testProperties());

        List<Double> embedding = provider.embed("VPN 开通流程");

        assertEquals(List.of(0.1, 0.2, 0.3), embedding);
        assertEquals(2, calls.get());
    }

    @Test
    void shouldNotRetryNonTransientClientErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        startServer(exchange -> {
            calls.incrementAndGet();
            write(exchange, 401, "{\"error\":\"bad_key\"}");
        });

        var provider = new DashScopeEmbeddingProvider(testProperties());

        assertThrows(RestClientResponseException.class, () -> provider.embed("VPN 开通流程"));
        assertEquals(1, calls.get());
    }

    @Test
    void shouldLimitConcurrentExternalEmbeddingRequests() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        CountDownLatch bothRequestsStarted = new CountDownLatch(2);
        startServer(exchange -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            bothRequestsStarted.countDown();
            Thread.sleep(120);
            active.decrementAndGet();
            write(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}");
        });

        RagEmbeddingProperties props = testProperties();
        props.setMaxConcurrentRequests(1);
        var provider = new DashScopeEmbeddingProvider(props);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var first = executor.submit(() -> provider.embed("VPN 开通流程"));
            var second = executor.submit(() -> provider.embed("报销流程"));

            assertEquals(List.of(0.1, 0.2, 0.3), first.get(2, TimeUnit.SECONDS));
            assertEquals(List.of(0.1, 0.2, 0.3), second.get(2, TimeUnit.SECONDS));
            assertTrue(bothRequestsStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, maxActive.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private RagEmbeddingProperties testProperties() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();
        props.setProvider("dashscope");
        props.setApiKey("test-api-key");
        props.setBaseUrl("http://localhost:" + server.getAddress().getPort() + "/compatible-mode/v1");
        props.setModel("text-embedding-v4");
        props.setDimensions(3);
        props.setBatchSize(1);
        props.setRetryMaxAttempts(2);
        props.setRetryInitialBackoffMs(1);
        props.setRetryMaxBackoffMs(5);
        props.setRateLimitPermitsPerSecond(0.0);
        props.setMaxConcurrentRequests(4);
        return props;
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/compatible-mode/v1/embeddings", exchange -> {
            try {
                handler.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                write(exchange, 500, "{\"error\":\"interrupted\"}");
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    private void write(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException, InterruptedException;
    }
}
