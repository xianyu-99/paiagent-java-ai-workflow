package com.paiagent.engine.llm;

import com.paiagent.engine.executor.impl.AbstractLLMNodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.service.LLMGlobalConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LLMProviderRegistryTest {

    @Test
    void normalizesKimiAndMoonshotAliases() {
        assertEquals("moonshot", LLMProviderRegistry.normalizeProvider("kimi"));
        assertEquals("moonshot", LLMProviderRegistry.normalizeProvider("Moonshot AI"));
        assertEquals("moonshot", LLMProviderRegistry.normalizeProvider("月之暗面"));
    }

    @Test
    void normalizesKimiCodeAliases() {
        assertEquals("kimi_code", LLMProviderRegistry.normalizeProvider("kimi code"));
        assertEquals("kimi_code", LLMProviderRegistry.normalizeProvider("kimi-for-coding"));
        assertEquals("kimi_code", LLMProviderRegistry.normalizeProvider("kimi编程"));
    }

    @Test
    void normalizesXiaomiMimoAliases() {
        assertEquals("mimo", LLMProviderRegistry.normalizeProvider("xiaomi"));
        assertEquals("mimo", LLMProviderRegistry.normalizeProvider("Xiaomi MiMo"));
        assertEquals("mimo", LLMProviderRegistry.normalizeProvider("小米 MiMo"));
    }

    @Test
    void resolvesMoonshotDefaultBaseUrl() {
        assertEquals(
                "https://api.moonshot.cn",
                LLMProviderRegistry.resolveBaseUrl("kimi", null)
        );
    }

    @Test
    void resolvesKimiCodeDefaultBaseUrl() {
        assertEquals(
                "https://api.kimi.com/coding",
                LLMProviderRegistry.resolveBaseUrl("kimi code", null)
        );
    }

    @Test
    void resolvesMimoDefaultBaseUrl() {
        assertEquals(
                "https://token-plan-cn.xiaomimimo.com",
                LLMProviderRegistry.resolveBaseUrl("xiaomi mimo", null)
        );
    }

    @Test
    void keepsCustomProviderAsOpenAICompatible() {
        assertEquals("siliconflow", LLMProviderRegistry.normalizeProvider("SiliconFlow"));
        assertEquals(LLMProviderRegistry.ApiType.OPENAI_COMPATIBLE, LLMProviderRegistry.getApiType("siliconflow"));
    }

    @Test
    void chatClientFactoryAcceptsKimiAndCustomOpenAICompatibleProviders() {
        ChatClientFactory factory = new ChatClientFactory();

        ChatClient kimiClient = assertDoesNotThrow(() ->
                factory.createClient("kimi", null, "sk-test", "kimi-k2.6", 0.7)
        );
        ChatClient customClient = assertDoesNotThrow(() ->
                factory.createClient("siliconflow", "https://api.siliconflow.cn", "sk-test", "Qwen/Qwen2.5-7B-Instruct", 0.7)
        );
        ChatClient mimoClient = assertDoesNotThrow(() ->
                factory.createClient("xiaomi mimo", null, "tp-test", "mimo-v2.5-pro", 0.7)
        );

        assertNotNull(kimiClient);
        assertNotNull(customClient);
        assertNotNull(mimoClient);
    }

    @Test
    void genericLlmNodeCanResolveKimiGlobalConfig() {
        LLMGlobalConfig globalConfig = new LLMGlobalConfig();
        globalConfig.setProvider("kimi");
        globalConfig.setConfigName("Kimi 默认");
        globalConfig.setApiKey("sk-test");
        globalConfig.setModel("kimi-k2.6");
        globalConfig.setTemperature(BigDecimal.valueOf(0.3));

        LLMGlobalConfigService configService = mock(LLMGlobalConfigService.class);
        when(configService.getDecryptedById(7L)).thenReturn(globalConfig);

        WorkflowNode node = new WorkflowNode();
        node.setId("llm-1");
        node.setType("llm");
        Map<String, Object> data = new HashMap<>();
        data.put("configId", 7L);
        data.put("prompt", "你好");
        node.setData(data);

        TestLlmExecutor executor = new TestLlmExecutor(configService);
        LLMNodeConfig nodeConfig = executor.extract(node);

        assertEquals("moonshot", nodeConfig.getProvider());
        assertEquals("https://api.moonshot.cn", nodeConfig.getApiUrl());
        assertEquals("sk-test", nodeConfig.getApiKey());
        assertEquals("kimi-k2.6", nodeConfig.getModel());
        assertEquals(0.3, nodeConfig.getTemperature());
        assertEquals(7L, nodeConfig.getConfigId());
    }

    @Test
    void genericLlmNodeCanResolveMimoGlobalConfig() {
        LLMGlobalConfig globalConfig = new LLMGlobalConfig();
        globalConfig.setProvider("xiaomi mimo");
        globalConfig.setConfigName("MiMo 默认");
        globalConfig.setApiKey("tp-test");
        globalConfig.setModel("mimo-v2.5-pro");
        globalConfig.setTemperature(BigDecimal.valueOf(0.7));

        LLMGlobalConfigService configService = mock(LLMGlobalConfigService.class);
        when(configService.getDecryptedById(8L)).thenReturn(globalConfig);

        WorkflowNode node = new WorkflowNode();
        node.setId("llm-2");
        node.setType("llm");
        Map<String, Object> data = new HashMap<>();
        data.put("configId", 8L);
        data.put("prompt", "你好");
        node.setData(data);

        TestLlmExecutor executor = new TestLlmExecutor(configService);
        LLMNodeConfig nodeConfig = executor.extract(node);

        assertEquals("mimo", nodeConfig.getProvider());
        assertEquals("https://token-plan-cn.xiaomimimo.com", nodeConfig.getApiUrl());
        assertEquals("tp-test", nodeConfig.getApiKey());
        assertEquals("mimo-v2.5-pro", nodeConfig.getModel());
    }

    private static class TestLlmExecutor extends AbstractLLMNodeExecutor {

        TestLlmExecutor(LLMGlobalConfigService configService) {
            this.llmGlobalConfigService = configService;
        }

        LLMNodeConfig extract(WorkflowNode node) {
            return extractConfig(node);
        }

        @Override
        protected String getNodeType() {
            return "llm";
        }
    }
}
