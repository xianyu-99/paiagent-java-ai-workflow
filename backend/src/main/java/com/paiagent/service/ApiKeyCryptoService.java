package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.config.JwtSecretProvider;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.function.Function;

@Service
public class ApiKeyCryptoService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyCryptoService.class);
    private static final String PREFIX = "enc:v1:";
    private static final String API_KEY_CONFIGURED_FIELD = "apiKeyConfigured";
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final JwtSecretProvider jwtSecretProvider;

    @Value("${paiagent.security.api-key-secret:}")
    private String configuredSecret;

    private SecretKeySpec keySpec;

    public ApiKeyCryptoService(JwtSecretProvider jwtSecretProvider) {
        this.jwtSecretProvider = jwtSecretProvider;
    }

    @PostConstruct
    public void init() {
        String secret = StringUtils.hasText(configuredSecret) ? configuredSecret : jwtSecretProvider.getSecret();
        if (!StringUtils.hasText(configuredSecret)) {
            log.warn("API_KEY_ENCRYPTION_SECRET is not configured; falling back to JWT secret for local development");
        }
        this.keySpec = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext) || isEncrypted(plaintext)) {
            return plaintext;
        }

        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(ciphertext, 0, payload, iv.length, ciphertext.length);

            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    public String decrypt(String value) {
        if (!StringUtils.hasText(value) || !isEncrypted(value)) {
            return value;
        }

        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[payload.length - IV_LENGTH_BYTES];
            System.arraycopy(payload, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(payload, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 解密失败，请检查 API_KEY_ENCRYPTION_SECRET 是否变更", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public String encryptApiKeysInJson(String json) {
        return transformApiKeysInJson(json, this::encrypt);
    }

    public String decryptApiKeysInJson(String json) {
        return transformApiKeysInJson(json, this::decrypt);
    }

    public String maskApiKeysInJson(String json) {
        if (!StringUtils.hasText(json)) {
            return json;
        }

        try {
            Object parsed = JSON.parse(json);
            maskApiKeyValue(parsed);
            return JSON.toJSONString(parsed);
        } catch (Exception e) {
            log.warn("Failed to mask apiKey fields in JSON payload; keeping original content", e);
            return json;
        }
    }

    public String preserveMissingApiKeysInJson(String incomingJson, String existingJson) {
        if (!StringUtils.hasText(incomingJson) || !StringUtils.hasText(existingJson)) {
            return incomingJson;
        }

        try {
            Object incoming = JSON.parse(incomingJson);
            Object existing = JSON.parse(existingJson);
            preserveApiKeyValue(incoming, existing);
            return JSON.toJSONString(incoming);
        } catch (Exception e) {
            log.warn("Failed to preserve apiKey fields in JSON payload; keeping incoming content", e);
            return incomingJson;
        }
    }

    private String transformApiKeysInJson(String json, Function<String, String> transformer) {
        if (!StringUtils.hasText(json)) {
            return json;
        }

        try {
            Object parsed = JSON.parse(json);
            transformValue(parsed, transformer);
            return JSON.toJSONString(parsed);
        } catch (Exception e) {
            log.warn("Failed to transform apiKey fields in JSON payload; keeping original content", e);
            return json;
        }
    }

    private void transformValue(Object value, Function<String, String> transformer) {
        if (value instanceof JSONObject object) {
            for (String key : new ArrayList<>(object.keySet())) {
                Object child = object.get(key);
                if ("apiKey".equals(key) && child instanceof String text && StringUtils.hasText(text)) {
                    object.put(key, transformer.apply(text));
                } else {
                    transformValue(child, transformer);
                }
            }
            return;
        }

        if (value instanceof JSONArray array) {
            for (Object item : array) {
                transformValue(item, transformer);
            }
        }
    }

    private void maskApiKeyValue(Object value) {
        if (value instanceof JSONObject object) {
            for (String key : new ArrayList<>(object.keySet())) {
                Object child = object.get(key);
                if ("apiKey".equals(key) && child instanceof String text && StringUtils.hasText(text)) {
                    object.put(key, "");
                    object.put(API_KEY_CONFIGURED_FIELD, true);
                } else {
                    maskApiKeyValue(child);
                }
            }
            return;
        }

        if (value instanceof JSONArray array) {
            for (Object item : array) {
                maskApiKeyValue(item);
            }
        }
    }

    private void preserveApiKeyValue(Object incoming, Object existing) {
        if (incoming instanceof JSONObject incomingObject && existing instanceof JSONObject existingObject) {
            Object incomingApiKey = incomingObject.get("apiKey");
            Object existingApiKey = existingObject.get("apiKey");
            if (isMissingApiKey(incomingApiKey) && existingApiKey instanceof String text && StringUtils.hasText(text)) {
                incomingObject.put("apiKey", text);
            }

            for (String key : new ArrayList<>(incomingObject.keySet())) {
                if ("apiKey".equals(key)) {
                    continue;
                }
                preserveApiKeyValue(incomingObject.get(key), existingObject.get(key));
            }
            return;
        }

        if (incoming instanceof JSONArray incomingArray && existing instanceof JSONArray existingArray) {
            Map<String, JSONObject> existingById = new java.util.HashMap<>();
            for (Object existingItem : existingArray) {
                if (existingItem instanceof JSONObject object) {
                    Object id = object.get("id");
                    if (id != null) {
                        existingById.put(String.valueOf(id), object);
                    }
                }
            }

            for (int i = 0; i < incomingArray.size(); i++) {
                Object incomingItem = incomingArray.get(i);
                Object existingItem = i < existingArray.size() ? existingArray.get(i) : null;
                if (incomingItem instanceof JSONObject incomingObject) {
                    Object id = incomingObject.get("id");
                    if (id != null) {
                        JSONObject matchedExisting = existingById.get(String.valueOf(id));
                        if (matchedExisting != null) {
                            existingItem = matchedExisting;
                        }
                    }
                }
                preserveApiKeyValue(incomingItem, existingItem);
            }
        }
    }

    private boolean isMissingApiKey(Object value) {
        return value == null || (value instanceof String text && !StringUtils.hasText(text));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密密钥初始化失败", e);
        }
    }
}
