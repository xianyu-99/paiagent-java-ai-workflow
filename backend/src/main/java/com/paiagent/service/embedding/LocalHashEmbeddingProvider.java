package com.paiagent.service.embedding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LocalHashEmbeddingProvider implements EmbeddingProvider {

    private static final int DIMENSION = 256;

    @Override
    public List<Double> embed(String text) {
        double[] vector = new double[DIMENSION];
        if (text == null || text.isBlank()) {
            return toList(vector);
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        for (String token : tokenize(normalized)) {
            int index = Math.floorMod(token.hashCode(), DIMENSION);
            vector[index] += 1.0;
        }

        normalize(vector);
        return toList(vector);
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        if (left.size() != right.size()) {
            return 0.0;
        }

        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.size(); i++) {
            double a = left.get(i);
            double b = right.get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    @Override
    public String provider() {
        return "local";
    }

    @Override
    public String model() {
        return "local-hash-embedding";
    }

    @Override
    public int dimensions() {
        return DIMENSION;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String[] words = text.split("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+");
        for (String word : words) {
            if (word == null || word.isBlank()) {
                continue;
            }
            tokens.add(word);
            addChineseBigrams(tokens, word);
        }
        return tokens;
    }

    private void addChineseBigrams(List<String> tokens, String word) {
        for (int i = 0; i < word.length() - 1; i++) {
            char first = word.charAt(i);
            char second = word.charAt(i + 1);
            if (isChinese(first) && isChinese(second)) {
                tokens.add("" + first + second);
            }
        }
    }

    private boolean isChinese(char c) {
        return c >= '\u4e00' && c <= '\u9fa5';
    }

    private void normalize(double[] vector) {
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        if (norm == 0.0) {
            return;
        }
        double sqrt = Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / sqrt;
        }
    }

    private List<Double> toList(double[] vector) {
        List<Double> list = new ArrayList<>(vector.length);
        for (double value : vector) {
            list.add(value);
        }
        return list;
    }
}
