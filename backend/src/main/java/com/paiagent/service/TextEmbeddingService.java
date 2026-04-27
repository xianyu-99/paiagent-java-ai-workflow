package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 轻量本地向量化实现，用于先跑通 RAG 闭环。
 * 后续可以替换为远程 embedding 模型，检索接口无需改动。
 */
@Service
public class TextEmbeddingService {

    private static final int DIMENSION = 256;

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

    public double cosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int size = Math.min(left.size(), right.size());
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < size; i++) {
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

    public String serialize(List<Double> embedding) {
        return JSON.toJSONString(embedding);
    }

    public List<Double> deserialize(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return List.of();
        }
        return JSON.parseArray(embeddingJson, Double.class);
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
