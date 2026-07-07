package com.paiagent.engine.llm.prompt;

import com.paiagent.config.PromptCacheProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class PromptCacheService {

    private static final PromptCacheService NOOP = new PromptCacheService(PromptCacheProperties.disabled());

    private final PromptCacheProperties properties;
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public PromptCacheService(PromptCacheProperties properties) {
        this.properties = properties == null ? PromptCacheProperties.disabled() : properties;
    }

    public static PromptCacheService noop() {
        return NOOP;
    }

    public PromptCacheResult cacheStablePrompt(String namespace, String content) {
        String safeContent = content == null ? "" : content;
        if (!cacheable(safeContent)) {
            return PromptCacheResult.uncached(safeContent);
        }

        long now = System.currentTimeMillis();
        String key = buildKey(namespace, safeContent);
        CacheEntry cached = entries.get(key);
        if (cached != null && !cached.expired(now)) {
            return new PromptCacheResult(
                    cached.content(),
                    true,
                    true,
                    key,
                    cached.content().length(),
                    cached.content().length()
            );
        }

        entries.put(key, new CacheEntry(safeContent, sequence.incrementAndGet(), now + ttlMillis()));
        evictIfNeeded();
        return new PromptCacheResult(safeContent, true, false, key, safeContent.length(), 0);
    }

    public int size() {
        return entries.size();
    }

    private boolean cacheable(String content) {
        return properties.isEnabled()
                && StringUtils.hasText(content)
                && content.length() >= Math.max(1, properties.getMinimumChars())
                && properties.getMaxEntries() > 0
                && properties.getTtlSeconds() > 0;
    }

    private long ttlMillis() {
        return Math.max(1L, properties.getTtlSeconds()) * 1000L;
    }

    private void evictIfNeeded() {
        int maxEntries = Math.max(1, properties.getMaxEntries());
        while (entries.size() > maxEntries) {
            String keyToEvict = entries.entrySet().stream()
                    .min(Comparator.comparingLong(entry -> entry.getValue().sequence()))
                    .map(Map.Entry::getKey)
                    .orElse(null);
            if (keyToEvict == null) {
                return;
            }
            entries.remove(keyToEvict);
        }
    }

    private String buildKey(String namespace, String content) {
        String safeNamespace = StringUtils.hasText(namespace) ? namespace.trim() : "prompt";
        return safeNamespace + ":" + sha256(content);
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    private record CacheEntry(String content, long sequence, long expiresAtMillis) {
        private boolean expired(long nowMillis) {
            return expiresAtMillis <= nowMillis;
        }
    }
}
