package com.paiagent.service.embedding;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

public final class EmbeddingApiKeyResolver {

    private static final List<String> ENV_KEY_CANDIDATES = List.of(
            "RAG_EMBEDDING_API_KEY",
            "Qwen_API_KEY",
            "QWEN_API_KEY",
            "DASHSCOPE_API_KEY",
            "API_KEY"
    );

    public EmbeddingApiKeyResolver() {
    }

    public static String resolve(String configuredApiKey) {
        return resolve(configuredApiKey, System.getenv());
    }

    public String resolve() {
        return resolve("", System.getenv());
    }

    static String resolve(String configuredApiKey, Map<String, String> environment) {
        if (isUsableApiKey(configuredApiKey)) {
            return configuredApiKey.trim();
        }
        if (environment == null || environment.isEmpty()) {
            return "";
        }

        for (String key : ENV_KEY_CANDIDATES) {
            String value = environment.get(key);
            if (isUsableApiKey(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static boolean isUsableApiKey(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase();
        return !normalized.equals("sk-placeholder")
                && !normalized.equals("placeholder")
                && !normalized.contains("your_")
                && !normalized.contains("your-")
                && !normalized.contains("replace_me")
                && !normalized.contains("change_me");
    }
}
