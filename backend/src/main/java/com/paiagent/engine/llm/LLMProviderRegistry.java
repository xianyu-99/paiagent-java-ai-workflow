package com.paiagent.engine.llm;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * LLM provider registry.
 *
 * <p>Provider names are used for display and grouping. The actual runtime
 * adapter is selected by API type so OpenAI-compatible providers can be added
 * without creating a new executor.</p>
 */
public final class LLMProviderRegistry {

    public enum ApiType {
        OPENAI_COMPATIBLE
    }

    private static final Map<String, String> PROVIDER_ALIASES = Map.ofEntries(
            Map.entry("open ai", "openai"),
            Map.entry("deep seek", "deepseek"),
            Map.entry("通义千问", "qwen"),
            Map.entry("stepfun", "step"),
            Map.entry("阶跃星辰", "step"),
            Map.entry("智谱", "zhipu"),
            Map.entry("ai ping", "ai_ping"),
            Map.entry("moonshot ai", "moonshot"),
            Map.entry("moonshot-ai", "moonshot"),
            Map.entry("kimi", "moonshot"),
            Map.entry("kimi moonshot", "moonshot"),
            Map.entry("kimi / moonshot", "moonshot"),
            Map.entry("月之暗面", "moonshot"),
            Map.entry("kimi code", "kimi_code"),
            Map.entry("kimi-code", "kimi_code"),
            Map.entry("kimi coding", "kimi_code"),
            Map.entry("kimi for coding", "kimi_code"),
            Map.entry("kimi-for-coding", "kimi_code"),
            Map.entry("kimi编程", "kimi_code"),
            Map.entry("xiaomi", "mimo"),
            Map.entry("xiaomi mimo", "mimo"),
            Map.entry("xiaomi-mimo", "mimo"),
            Map.entry("小米", "mimo"),
            Map.entry("小米mimo", "mimo"),
            Map.entry("小米 mimo", "mimo")
    );

    private static final Map<String, String> DEFAULT_BASE_URLS = Map.ofEntries(
            Map.entry("openai", "https://api.openai.com"),
            Map.entry("deepseek", "https://api.deepseek.com"),
            Map.entry("qwen", "https://dashscope.aliyuncs.com/compatible-mode"),
            Map.entry("moonshot", "https://api.moonshot.cn"),
            Map.entry("kimi_code", "https://api.kimi.com/coding"),
            Map.entry("mimo", "https://token-plan-cn.xiaomimimo.com")
    );

    private LLMProviderRegistry() {
    }

    public static String normalizeProvider(String provider) {
        String trimmed = trimToNull(provider);
        if (trimmed == null) {
            return null;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT);
        return PROVIDER_ALIASES.getOrDefault(normalized, normalized);
    }

    public static Optional<String> getDefaultBaseUrl(String provider) {
        String normalizedProvider = normalizeProvider(provider);
        if (normalizedProvider == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(DEFAULT_BASE_URLS.get(normalizedProvider));
    }

    public static String resolveBaseUrl(String provider, String apiUrl) {
        String trimmedApiUrl = trimToNull(apiUrl);
        if (trimmedApiUrl != null) {
            return trimmedApiUrl;
        }
        return getDefaultBaseUrl(provider).orElse(null);
    }

    public static ApiType getApiType(String provider) {
        return ApiType.OPENAI_COMPATIBLE;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
