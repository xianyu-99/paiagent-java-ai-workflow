package com.paiagent.engine.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * ChatClient动态工厂
 * 根据节点配置在运行时创建不同类型的ChatClient实例
 */
@Slf4j
@Component
public class ChatClientFactory {

    private static final String CHAT_COMPLETIONS_SUFFIX = "/v1/chat/completions";

    private static final String V1_SUFFIX = "/v1";

    /**
     * 根据提供商和配置创建ChatClient
     *
     * @param provider    提供商标识
     * @param apiUrl      API端点URL
     * @param apiKey      API密钥
     * @param model       模型名称
     * @param temperature 温度参数
     * @return ChatClient实例
     */
    public ChatClient createClient(String provider, String apiUrl, String apiKey,
                                   String model, Double temperature) {
        return createClientWithFunctions(provider, apiUrl, apiKey, model, temperature, List.of());
    }

    /**
     * 创建带 Function Calling 支持的 ChatClient
     *
     * @param provider    提供商标识
     * @param apiUrl      API端点URL
     * @param apiKey      API密钥
     * @param model       模型名称
     * @param temperature 温度参数
     * @param functions   函数回调列表
     * @return ChatClient实例
     */
    public ChatClient createClientWithFunctions(String provider, String apiUrl, String apiKey,
                                                 String model, Double temperature,
                                                 List<FunctionCallback> functions) {
        List<FunctionCallback> safeFunctions = functions == null ? List.of() : functions;
        String normalizedProvider = LLMProviderRegistry.normalizeProvider(provider);
        String resolvedApiUrl = LLMProviderRegistry.resolveBaseUrl(normalizedProvider, apiUrl);
        log.info("创建ChatClient - 类型: {}, URL: {}, 模型: {}, 温度: {}, 函数数量: {}",
                normalizedProvider, resolvedApiUrl, model, temperature, safeFunctions.size());

        ChatModel chatModel = switch (LLMProviderRegistry.getApiType(normalizedProvider)) {
            case OPENAI_COMPATIBLE ->
                    createOpenAICompatibleModel(resolvedApiUrl, apiKey, model, temperature);
        };

        ChatClient.Builder builder = ChatClient.builder(chatModel);

        // 注册函数 - 直接传入 FunctionCallback 实例
        if (!safeFunctions.isEmpty()) {
            builder.defaultFunctions(safeFunctions.toArray(new FunctionCallback[0]));
            for (FunctionCallback function : safeFunctions) {
                log.debug("注册函数: {}", function.getName());
            }
        }

        return builder.build();
    }

    /**
     * 创建 OpenAI 兼容的 ChatModel。
     */
    private ChatModel createOpenAICompatibleModel(String apiUrl, String apiKey,
                                                   String model, Double temperature) {
        String normalizedApiUrl = normalizeBaseUrl(apiUrl);
        if (!normalizedApiUrl.equals(apiUrl)) {
            log.warn("检测到 OpenAI 兼容接口地址包含路径后缀，已自动归一化: {} -> {}", apiUrl, normalizedApiUrl);
        }

        // 使用构造函数创建OpenAiApi（支持自定义baseUrl）
        OpenAiApi openAiApi = new OpenAiApi(normalizedApiUrl, apiKey);

        // 创建ChatModel并配置选项
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(model)
                .temperature(temperature)
                .build();

        return new OpenAiChatModel(openAiApi, options);
    }

    /**
     * Spring AI 的 OpenAiApi 会自行拼接 /v1/chat/completions，
     * 这里统一将用户输入的 URL 归一化为服务根地址，兼容误填完整接口地址的情况。
     */
    private String normalizeBaseUrl(String apiUrl) {
        String normalized = stripTrailingSlash(apiUrl == null ? "" : apiUrl.trim());
        normalized = stripSuffixIgnoreCase(normalized, CHAT_COMPLETIONS_SUFFIX);
        normalized = stripSuffixIgnoreCase(normalized, V1_SUFFIX);
        return stripTrailingSlash(normalized);
    }

    private String stripSuffixIgnoreCase(String value, String suffix) {
        String lowerValue = value.toLowerCase(Locale.ROOT);
        if (!lowerValue.endsWith(suffix)) {
            return value;
        }
        return value.substring(0, value.length() - suffix.length());
    }

    private String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

}
