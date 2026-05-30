package com.paiagent.service;

import com.paiagent.common.VectorMath;
import com.paiagent.config.RagEmbeddingProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
