package com.paiagent.service;

import com.paiagent.common.VectorMath;
import com.paiagent.config.RagEmbeddingProperties;
import com.paiagent.service.embedding.EmbeddingProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextEmbeddingServiceTest {

    private static TextEmbeddingService createService() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();
        props.setProvider("dashscope");
        props.setApiKey("dummy-key-for-test");
        props.setModel("text-embedding-v4");
        props.setDimensions(1024);
        return new TextEmbeddingService(props);
    }

    @Test
    void shouldSerializeAndDeserializeEmbedding() {
        var service = createService();
        List<Double> embedding = List.of(0.1, 0.2, 0.3);

        String json = service.serialize(embedding);
        List<Double> recovered = service.deserialize(json);

        assertEquals(embedding, recovered);
    }

    @Test
    void shouldReturnEmptyListForBlankJson() {
        var service = createService();
        assertTrue(service.deserialize("").isEmpty());
        assertTrue(service.deserialize("   ").isEmpty());
        assertTrue(service.deserialize(null).isEmpty());
    }

    @Test
    void shouldComputeCosineSimilarity() {
        List<Double> a = List.of(1.0, 0.0, 0.0);
        List<Double> b = List.of(1.0, 0.0, 0.0);
        List<Double> c = List.of(0.0, 1.0, 0.0);

        assertEquals(1.0, VectorMath.cosine(a, b), 1e-9);
        assertEquals(0.0, VectorMath.cosine(a, c), 1e-9);
    }

    @Test
    void shouldHandleNullOrEmptyVectorsInCosine() {
        List<Double> v = List.of(1.0, 2.0);
        assertEquals(0.0, VectorMath.cosine(null, v));
        assertEquals(0.0, VectorMath.cosine(v, null));
        assertEquals(0.0, VectorMath.cosine(List.of(), v));
        assertEquals(0.0, VectorMath.cosine(v, List.of()));
    }

    @Test
    void shouldHandleMismatchedDimensionsInCosine() {
        List<Double> a = List.of(1.0, 2.0);
        List<Double> b = List.of(1.0, 2.0, 3.0);
        assertEquals(0.0, VectorMath.cosine(a, b));
    }

    @Test
    void shouldComputeSha256() {
        var service = createService();
        String hash = service.sha256("test");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }

    @Test
    void isCompatibleShouldMatchProviderModelAndDimensions() {
        var service = createService();
        String provider = service.provider();
        String model = service.model();
        int dims = service.dimensions();

        assertTrue(service.isCompatible(provider, model, dims));
        assertFalse(service.isCompatible("other", model, dims));
        assertFalse(service.isCompatible(provider, "other", dims));
        assertFalse(service.isCompatible(provider, model, dims + 1));
    }

    @Test
    void shouldCacheSingleQueryEmbeddingByProviderModelDimensionAndText() {
        var provider = new CountingEmbeddingProvider();
        var props = new RagEmbeddingProperties();
        props.setCacheEnabled(true);
        props.setCacheTtlSeconds(60);
        props.setCacheMaxSize(100);
        var service = new TextEmbeddingService(provider, props);

        List<Double> first = service.embed(" VPN 开通流程 ");
        List<Double> second = service.embed("VPN 开通流程");

        assertEquals(first, second);
        assertEquals(1, provider.singleCalls.get());
    }

    @Test
    void shouldNotUseQueryCacheForBatchEmbedding() {
        var provider = new CountingEmbeddingProvider();
        var props = new RagEmbeddingProperties();
        props.setCacheEnabled(true);
        var service = new TextEmbeddingService(provider, props);

        service.embedBatch(List.of("a", "b"));
        service.embedBatch(List.of("a", "b"));

        assertEquals(2, provider.batchCalls.get());
    }

    private static class CountingEmbeddingProvider implements EmbeddingProvider {
        private final AtomicInteger singleCalls = new AtomicInteger();
        private final AtomicInteger batchCalls = new AtomicInteger();

        @Override
        public List<Double> embed(String text) {
            int call = singleCalls.incrementAndGet();
            return List.of((double) call, 0.1, 0.2);
        }

        @Override
        public List<List<Double>> embedBatch(List<String> texts) {
            batchCalls.incrementAndGet();
            List<List<Double>> result = new ArrayList<>();
            for (String ignored : texts) {
                result.add(List.of(1.0, 0.0, 0.0));
            }
            return result;
        }

        @Override
        public double cosine(List<Double> left, List<Double> right) {
            return VectorMath.cosine(left, right);
        }

        @Override
        public String provider() {
            return "test";
        }

        @Override
        public String model() {
            return "test-model";
        }

        @Override
        public int dimensions() {
            return 3;
        }
    }
}
