package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.config.JwtSecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyCryptoServiceTest {

    @Test
    void shouldMaskAndPreserveWorkflowNodeApiKeys() {
        ApiKeyCryptoService service = createService();
        String flowData = JSON.toJSONString(Map.of(
                "nodes",
                List.of(Map.of(
                        "id", "tts-1",
                        "data", Map.of(
                                "type", "tts",
                                "apiKey", "sk-secret",
                                "model", "qwen3-tts-flash"
                        )
                )),
                "edges",
                List.of()
        ));

        String masked = service.maskApiKeysInJson(flowData);
        JSONObject maskedData = JSON.parseObject(masked)
                .getJSONArray("nodes")
                .getJSONObject(0)
                .getJSONObject("data");

        assertEquals("", maskedData.getString("apiKey"));
        assertTrue(maskedData.getBooleanValue("apiKeyConfigured"));
        assertFalse(masked.contains("sk-secret"));

        String preserved = service.preserveMissingApiKeysInJson(masked, flowData);
        JSONObject preservedData = JSON.parseObject(preserved)
                .getJSONArray("nodes")
                .getJSONObject(0)
                .getJSONObject("data");

        assertEquals("sk-secret", preservedData.getString("apiKey"));
    }

    private ApiKeyCryptoService createService() {
        JwtSecretProvider jwtSecretProvider = mock(JwtSecretProvider.class);
        when(jwtSecretProvider.getSecret()).thenReturn("test-secret-key-for-api-key-crypto-service");

        ApiKeyCryptoService service = new ApiKeyCryptoService(jwtSecretProvider);
        ReflectionTestUtils.setField(service, "configuredSecret", "test-api-key-encryption-secret");
        service.init();
        return service;
    }
}
