package com.paiagent.service.embedding;

import java.util.List;

public interface EmbeddingProvider {

    List<Double> embed(String text);

    List<List<Double>> embedBatch(List<String> texts);

    double cosine(List<Double> left, List<Double> right);

    String provider();

    String model();

    int dimensions();
}
