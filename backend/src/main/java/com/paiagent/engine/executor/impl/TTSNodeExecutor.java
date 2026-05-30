package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.dashscope.aigc.multimodalconversation.AudioParameters;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.reference.WorkflowReferenceResolver;
import com.paiagent.service.MinioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

@Slf4j
@Component
public class TTSNodeExecutor implements NodeExecutor {
    
    private static final int MAX_TTS_INPUT_LENGTH = 400;
    private static final int MAX_AUDIO_DOWNLOAD_BYTES = 50 * 1024 * 1024;
    private static final String DEFAULT_QWEN_PROVIDER = "qwen";
    private static final String MIMO_PROVIDER = "mimo";
    private static final String DEFAULT_MIMO_API_URL = "https://token-plan-cn.xiaomimimo.com/v1";
    private static final String DEFAULT_MIMO_TTS_MODEL = "mimo-v2-tts";
    private static final String DEFAULT_MIMO_VOICE = "mimo_default";
    private static final String DEFAULT_AUDIO_FORMAT = "wav";
    private static final int WAV_MIN_HEADER_LENGTH = 12;
    
    @Autowired
    private MinioService minioService;

    @Autowired
    @Qualifier("ttsTaskExecutor")
    private Executor ttsTaskExecutor;

    @Value("${MIMO_API_KEY:}")
    private String defaultMimoApiKey;

    @Value("${MIMO_API_URL:" + DEFAULT_MIMO_API_URL + "}")
    private String defaultMimoApiUrl;

    @Value("${MIMO_TTS_MODEL:" + DEFAULT_MIMO_TTS_MODEL + "}")
    private String defaultMimoTtsModel;

    @Value("${MIMO_TTS_VOICE:" + DEFAULT_MIMO_VOICE + "}")
    private String defaultMimoTtsVoice;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    
    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }
    
    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input, Consumer<ExecutionEvent> progressCallback) throws Exception {
        String text = normalizeTtsInputText(extractInputText(node, input));
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("输入文本不能为空");
        }
        
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        String provider = normalizeProvider(stringValue(data.getOrDefault("provider", DEFAULT_QWEN_PROVIDER)));
        if (MIMO_PROVIDER.equals(provider)) {
            return executeMimoTts(node, text, data, progressCallback);
        }

        String apiKey = (String) data.get("apiKey");
        String model = (String) data.getOrDefault("model", "qwen3-tts-flash");
        String voiceStr = (String) data.getOrDefault("voice", "Cherry");
        String languageType = (String) data.getOrDefault("languageType", "Auto");
        
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("阿里百炼 API Key 不能为空,请在节点配置中设置");
        }
        
        log.info("TTS 节点执行 - 模型: {}, 文本长度: {}, 音色: {}, 语言类型: {}", 
                model, text.length(), voiceStr, languageType);
        
        AudioParameters.Voice voice = convertVoice(voiceStr);
        
        List<String> textChunks = splitText(text, MAX_TTS_INPUT_LENGTH);
        log.info("文本分割为 {} 个片段", textChunks.size());
        
        if (progressCallback != null) {
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("totalChunks", textChunks.size());
            progressData.put("currentChunk", 0);
            progressCallback.accept(ExecutionEvent.nodeProgress(
                node.getId(), 
                node.getType(), 
                "文本已分割为 " + textChunks.size() + " 个片段", 
                progressData
            ));
        }
        
        List<byte[]> audioChunks = new ArrayList<>();
        List<CompletableFuture<byte[]>> futures = new ArrayList<>();
        
        for (int i = 0; i < textChunks.size(); i++) {
            final int chunkIndex = i;
            final String chunk = textChunks.get(i);
            
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    int utf8ByteLength = chunk.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    log.info("处理第 {}/{} 个片段, 字符数: {}, UTF-8 字节数: {}", 
                            chunkIndex + 1, textChunks.size(), chunk.length(), utf8ByteLength);
                    
                    if (progressCallback != null) {
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("totalChunks", textChunks.size());
                        progressData.put("currentChunk", chunkIndex + 1);
                        progressData.put("chunkText", chunk.substring(0, Math.min(50, chunk.length())) + "...");
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                            node.getId(), 
                            node.getType(), 
                            "正在处理第 " + (chunkIndex + 1) + "/" + textChunks.size() + " 个片段", 
                            progressData
                        ));
                    }
                    
                    MultiModalConversationParam param = MultiModalConversationParam.builder()
                            .apiKey(apiKey)
                            .model(model)
                            .text(chunk)
                            .voice(voice)
                            .languageType(languageType)
                            .build();
                    
                    MultiModalConversation conv = new MultiModalConversation();
                    MultiModalConversationResult result = conv.call(param);
                    String audioUrl = result.getOutput().getAudio().getUrl();
                    
                    if (!StringUtils.hasText(audioUrl)) {
                        throw new RuntimeException("阿里百炼 TTS 返回的音频URL为空 (片段 " + (chunkIndex + 1) + ")");
                    }
                    
                    log.info("第 {}/{} 个片段音频URL: {}", chunkIndex + 1, textChunks.size(), audioUrl);
                    
                    byte[] audioData = downloadAudio(audioUrl);
                    
                    if (progressCallback != null) {
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("totalChunks", textChunks.size());
                        progressData.put("currentChunk", chunkIndex + 1);
                        progressData.put("completedChunks", chunkIndex + 1);
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                            node.getId(), 
                            node.getType(), 
                            "已完成第 " + (chunkIndex + 1) + "/" + textChunks.size() + " 个片段", 
                            progressData
                        ));
                    }
                    
                    return audioData;
                } catch (Exception e) {
                    throw new RuntimeException("处理第 " + (chunkIndex + 1) + " 个片段失败: " + e.getMessage(), e);
                }
            }, ttsTaskExecutor);
            
            futures.add(future);
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        
        for (CompletableFuture<byte[]> future : futures) {
            audioChunks.add(future.get());
        }
        
        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                node.getId(), 
                node.getType(), 
                "正在合并 " + audioChunks.size() + " 个音频片段...", 
                null
            ));
        }
        
        byte[] mergedAudio = mergeWavFiles(audioChunks);
        
        String fileName = "audio_" + UUID.randomUUID() + ".wav";
        String objectName = "audio/" + fileName;
        String minioUrl = minioService.uploadFromBytes(mergedAudio, objectName, "audio/wav");
        
        Map<String, Object> output = new HashMap<>();
        output.put("audioUrl", minioUrl);
        output.put("fileName", fileName);
        output.put("output", minioUrl);
        output.put("chunks", textChunks.size());
        
        log.info("TTS 合并音频已上传到 MinIO: {}, 共 {} 个片段", minioUrl, textChunks.size());
        
        return output;
    }

    private Map<String, Object> executeMimoTts(WorkflowNode node,
                                               String text,
                                               Map<String, Object> data,
                                               Consumer<ExecutionEvent> progressCallback) throws Exception {
        String apiKey = firstText(stringValue(data.get("apiKey")), defaultMimoApiKey);
        String apiUrl = firstText(stringValue(data.get("apiUrl")), defaultMimoApiUrl, DEFAULT_MIMO_API_URL);
        String model = firstText(stringValue(data.get("model")), defaultMimoTtsModel, DEFAULT_MIMO_TTS_MODEL);
        String voice = firstText(stringValue(data.get("voice")), defaultMimoTtsVoice, DEFAULT_MIMO_VOICE);
        String style = firstText(stringValue(data.get("style")), "");

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("MiMo API Key 不能为空，请在节点配置或环境变量 MIMO_API_KEY 中设置");
        }

        List<String> textChunks = splitText(text, MAX_TTS_INPUT_LENGTH);
        log.info("MiMo TTS 节点执行 - API: {}, 模型: {}, 文本长度: {}, 音色: {}, 片段数: {}",
                maskApiUrl(apiUrl), model, text.length(), voice, textChunks.size());

        if (progressCallback != null) {
            Map<String, Object> progressData = new HashMap<>();
            progressData.put("provider", MIMO_PROVIDER);
            progressData.put("totalChunks", textChunks.size());
            progressData.put("currentChunk", 0);
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(),
                    node.getType(),
                    "MiMo TTS 文本已分割为 " + textChunks.size() + " 个片段",
                    progressData
            ));
        }

        List<CompletableFuture<byte[]>> futures = new ArrayList<>();
        for (int i = 0; i < textChunks.size(); i++) {
            final int chunkIndex = i;
            final String chunk = applyMimoStyle(textChunks.get(i), style);
            CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
                try {
                    if (progressCallback != null) {
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("provider", MIMO_PROVIDER);
                        progressData.put("totalChunks", textChunks.size());
                        progressData.put("currentChunk", chunkIndex + 1);
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                                node.getId(),
                                node.getType(),
                                "正在处理 MiMo TTS 第 " + (chunkIndex + 1) + "/" + textChunks.size() + " 个片段",
                                progressData
                        ));
                    }

                    byte[] audioData = synthesizeMimoChunk(apiUrl, apiKey, model, voice, chunk);

                    if (progressCallback != null) {
                        Map<String, Object> progressData = new HashMap<>();
                        progressData.put("provider", MIMO_PROVIDER);
                        progressData.put("totalChunks", textChunks.size());
                        progressData.put("currentChunk", chunkIndex + 1);
                        progressData.put("completedChunks", chunkIndex + 1);
                        progressCallback.accept(ExecutionEvent.nodeProgress(
                                node.getId(),
                                node.getType(),
                                "已完成 MiMo TTS 第 " + (chunkIndex + 1) + "/" + textChunks.size() + " 个片段",
                                progressData
                        ));
                    }
                    return audioData;
                } catch (Exception e) {
                    throw new RuntimeException("处理 MiMo TTS 第 " + (chunkIndex + 1) + " 个片段失败: " + e.getMessage(), e);
                }
            }, ttsTaskExecutor);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        List<byte[]> audioChunks = new ArrayList<>();
        for (CompletableFuture<byte[]> future : futures) {
            audioChunks.add(future.get());
        }

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                    node.getId(),
                    node.getType(),
                    "正在合并 " + audioChunks.size() + " 个 MiMo 音频片段...",
                    null
            ));
        }

        byte[] mergedAudio = mergeWavFiles(audioChunks);
        String fileName = "audio_" + UUID.randomUUID() + ".wav";
        String objectName = "audio/" + fileName;
        String minioUrl = minioService.uploadFromBytes(mergedAudio, objectName, "audio/wav");

        Map<String, Object> output = new HashMap<>();
        output.put("audioUrl", minioUrl);
        output.put("fileName", fileName);
        output.put("output", minioUrl);
        output.put("chunks", textChunks.size());
        output.put("provider", MIMO_PROVIDER);

        log.info("MiMo TTS 合并音频已上传到 MinIO: {}, 共 {} 个片段", minioUrl, textChunks.size());
        return output;
    }

    private byte[] synthesizeMimoChunk(String apiUrl,
                                       String apiKey,
                                       String model,
                                       String voice,
                                       String text) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);

        JSONArray messages = new JSONArray();
        JSONObject assistantMessage = new JSONObject();
        assistantMessage.put("role", "assistant");
        assistantMessage.put("content", text);
        messages.add(assistantMessage);
        body.put("messages", messages);

        JSONObject audio = new JSONObject();
        audio.put("format", DEFAULT_AUDIO_FORMAT);
        audio.put("voice", voice);
        body.put("audio", audio);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(buildMimoChatCompletionsUrl(apiUrl)))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("api-key", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("MiMo TTS 调用失败，HTTP 状态码: "
                    + response.statusCode() + ", 响应: " + summarizeForError(response.body()));
        }

        Object parsed = JSON.parse(response.body());
        String audioData = findMimoAudioData(parsed, false);
        if (StringUtils.hasText(audioData)) {
            return decodeBase64Audio(audioData);
        }

        String audioUrl = findMimoAudioUrl(parsed, false);
        if (StringUtils.hasText(audioUrl)) {
            return downloadAudio(audioUrl);
        }

        throw new IllegalStateException("MiMo TTS 响应中未找到音频数据，请检查返回字段: "
                + summarizeForError(response.body()));
    }

    private String buildMimoChatCompletionsUrl(String apiUrl) {
        String normalized = stripTrailingSlash(apiUrl);
        if (normalized.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            return normalized;
        }
        return normalized + "/chat/completions";
    }

    private String applyMimoStyle(String text, String style) {
        if (!StringUtils.hasText(style) || text.trim().startsWith("<style>")) {
            return text;
        }
        return "<style>" + style.trim() + "</style>" + text;
    }

    private String findMimoAudioData(Object value, boolean insideAudio) {
        if (value instanceof JSONObject object) {
            Object audioObject = object.get("audio");
            if (audioObject != null) {
                String data = findMimoAudioData(audioObject, true);
                if (StringUtils.hasText(data)) {
                    return data;
                }
            }
            Object dataObject = object.get("data");
            if (insideAudio && dataObject instanceof String data && StringUtils.hasText(data)) {
                return data;
            }
            for (String key : object.keySet()) {
                String data = findMimoAudioData(object.get(key), insideAudio);
                if (StringUtils.hasText(data)) {
                    return data;
                }
            }
        }
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                String data = findMimoAudioData(item, insideAudio);
                if (StringUtils.hasText(data)) {
                    return data;
                }
            }
        }
        return null;
    }

    private String findMimoAudioUrl(Object value, boolean insideAudio) {
        if (value instanceof JSONObject object) {
            Object audioObject = object.get("audio");
            if (audioObject != null) {
                String url = findMimoAudioUrl(audioObject, true);
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
            Object urlObject = object.get("url");
            if (insideAudio && urlObject instanceof String url && StringUtils.hasText(url)) {
                return url;
            }
            for (String key : object.keySet()) {
                String url = findMimoAudioUrl(object.get(key), insideAudio);
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
        }
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                String url = findMimoAudioUrl(item, insideAudio);
                if (StringUtils.hasText(url)) {
                    return url;
                }
            }
        }
        return null;
    }

    private byte[] decodeBase64Audio(String audioData) {
        String normalized = audioData.trim();
        int commaIndex = normalized.indexOf(',');
        if (normalized.startsWith("data:") && commaIndex >= 0) {
            normalized = normalized.substring(commaIndex + 1);
        }
        normalized = normalized.replaceAll("\\s+", "");
        try {
            return Base64.getDecoder().decode(normalized);
        } catch (IllegalArgumentException ignored) {
            return Base64.getUrlDecoder().decode(normalized);
        }
    }
    
    private AudioParameters.Voice convertVoice(String voiceStr) {
        try {
            return AudioParameters.Voice.valueOf(voiceStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("未知音色: {}, 使用默认音色 CHERRY", voiceStr);
            return AudioParameters.Voice.CHERRY;
        }
    }
    
    private String extractInputText(WorkflowNode node, Map<String, Object> input) {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        List<Map<String, Object>> inputParams = (List<Map<String, Object>>) data.get("inputParams");
        
        if (inputParams != null && !inputParams.isEmpty()) {
            String firstResolvedText = null;
            for (Map<String, Object> param : inputParams) {
                String paramName = (String) param.get("name");
                String resolved = resolveTtsInputParam(param, input);
                if (!StringUtils.hasText(resolved)) {
                    continue;
                }
                if ("text".equals(paramName)) {
                    return resolved;
                }
                if (firstResolvedText == null) {
                    firstResolvedText = resolved;
                }
            }
            if (StringUtils.hasText(firstResolvedText)) {
                return firstResolvedText;
            }
        }
        
        String text = stringValue(input.get("output"));
        if (StringUtils.hasText(text)) {
            return text;
        }
        
        text = stringValue(input.get("input"));
        if (StringUtils.hasText(text)) {
            return text;
        }
        
        return stringValue(input.get("text"));
    }

    private String resolveTtsInputParam(Map<String, Object> param, Map<String, Object> input) {
        String type = stringValue(param.get("type"));
        if ("input".equals(type)) {
            return stringValue(param.get("value"));
        }
        if ("reference".equals(type)) {
            Object value = WorkflowReferenceResolver.resolve(stringValue(param.get("referenceNode")), input);
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private String normalizeTtsInputText(String text) {
        String normalized = extractOutputFromJsonObject(text);
        if (!StringUtils.hasText(normalized)) {
            return normalized;
        }

        normalized = normalized.trim()
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        normalized = normalized.replaceAll("\\s*\\[(停顿|笑声|强调)\\]\\s*", "\n");
        normalized = normalized.replaceAll("(?m)^\\s*(?:主持人\\s*)?[A-Za-z]\\s*[:：]\\s*", "");
        normalized = normalized.replaceAll("([。！？!?；;])\\s*(?:主持人\\s*)?[A-Za-z]\\s*[:：]\\s*", "$1\n");
        normalized = normalized.replaceAll("[\\t\\x0B\\f ]+", " ");
        normalized = normalized.replaceAll(" *\\n+ *", "\n");
        normalized = normalized.replaceAll("\\n{3,}", "\n\n");
        return normalized.trim();
    }

    private String extractOutputFromJsonObject(String text) {
        if (!StringUtils.hasText(text)) {
            return text;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return text;
        }
        try {
            Object parsed = JSON.parse(trimmed);
            if (parsed instanceof JSONObject object) {
                Object output = object.get("output");
                if (output instanceof String outputText && StringUtils.hasText(outputText)) {
                    return outputText;
                }
            }
        } catch (Exception ignored) {
            return text;
        }
        return text;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstText(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalizeProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            return DEFAULT_QWEN_PROVIDER;
        }
        String normalized = provider.trim().toLowerCase(Locale.ROOT);
        if ("dashscope".equals(normalized) || "aliyun".equals(normalized) || "qwen-tts".equals(normalized)) {
            return DEFAULT_QWEN_PROVIDER;
        }
        if ("xiaomi".equals(normalized) || "xiaomi-mimo".equals(normalized) || "mimo-tts".equals(normalized)) {
            return MIMO_PROVIDER;
        }
        return normalized;
    }

    private String stripTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private String summarizeForError(String value) {
        if (value == null) {
            return "";
        }
        int maxLength = 800;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }

    private String maskApiUrl(String apiUrl) {
        return apiUrl == null ? "" : apiUrl.replaceAll("(?i)(api[_-]?key=)[^&]+", "$1***");
    }
    
    @Override
    public String getSupportedNodeType() {
        return "tts";
    }
    
    private List<String> splitText(String text, int maxLength) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        
        while (start < text.length()) {
            int end = Math.min(start + maxLength, text.length());
            
            while (end > start) {
                String candidate = text.substring(start, end);
                int byteLength = candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                
                if (byteLength <= 600) {
                    if (end < text.length()) {
                        int lastPunctuation = findLastPunctuation(text, start, end);
                        if (lastPunctuation > start) {
                            end = lastPunctuation + 1;
                            candidate = text.substring(start, end);
                        }
                    }
                    
                    chunks.add(candidate);
                    start = end;
                    break;
                }
                
                end -= 10;
            }
            
            if (end <= start) {
                end = start + 1;
                while (end <= text.length()) {
                    String candidate = text.substring(start, end);
                    int byteLength = candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                    if (byteLength > 600) {
                        if (end - 1 > start) {
                            chunks.add(text.substring(start, end - 1));
                            start = end - 1;
                        } else {
                            throw new IllegalArgumentException("单个字符超过 600 字节,无法处理");
                        }
                        break;
                    }
                    end++;
                }
            }
        }
        
        return chunks;
    }
    
    private int findLastPunctuation(String text, int start, int end) {
        String punctuations = "。！？；,.!?;";
        for (int i = end - 1; i >= start; i--) {
            if (punctuations.indexOf(text.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
    
    private byte[] downloadAudio(String audioUrl) throws Exception {
        URL url = new URL(audioUrl);
        String protocol = url.getProtocol();
        if (!"https".equalsIgnoreCase(protocol) && !"http".equalsIgnoreCase(protocol)) {
            throw new IllegalArgumentException("不支持的音频下载协议: " + protocol);
        }

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        
        int statusCode = conn.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("音频下载失败，HTTP 状态码: " + statusCode);
        }

        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                if (baos.size() + bytesRead > MAX_AUDIO_DOWNLOAD_BYTES) {
                    throw new IllegalStateException("音频文件超过下载大小限制");
                }
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }
    
    private byte[] mergeWavFiles(List<byte[]> audioChunks) throws Exception {
        if (audioChunks.isEmpty()) {
            throw new IllegalArgumentException("音频片段列表为空");
        }
        
        if (audioChunks.size() == 1) {
            return normalizeWavHeader(audioChunks.get(0));
        }
        
        byte[] firstChunk = audioChunks.get(0);
        WavLayout firstLayout = parseWavLayout(firstChunk);
        
        ByteArrayOutputStream mergedStream = new ByteArrayOutputStream();
        
        byte[] header = Arrays.copyOf(firstChunk, firstLayout.dataOffset());
        mergedStream.write(header);
        
        for (byte[] chunk : audioChunks) {
            WavLayout layout = parseWavLayout(chunk);
            mergedStream.write(chunk, layout.dataOffset(), layout.dataSize());
        }
        
        byte[] mergedData = mergedStream.toByteArray();
        
        int dataSize = mergedData.length - firstLayout.dataOffset();
        int fileSize = mergedData.length - 8;
        
        writeLittleEndianInt(mergedData, 4, fileSize);
        writeLittleEndianInt(mergedData, firstLayout.dataSizeOffset(), dataSize);
        
        return mergedData;
    }

    /**
     * 修正 WAV 头里的长度字段，避免上游返回异常头信息导致播放器时长识别错误。
     */
    private byte[] normalizeWavHeader(byte[] wavData) {
        WavLayout layout = parseWavLayout(wavData);
        byte[] normalized = Arrays.copyOf(wavData, wavData.length);
        int dataSize = normalized.length - layout.dataOffset();
        int fileSize = normalized.length - 8;

        writeLittleEndianInt(normalized, 4, fileSize);
        writeLittleEndianInt(normalized, layout.dataSizeOffset(), dataSize);

        return normalized;
    }

    private WavLayout parseWavLayout(byte[] wavData) {
        if (wavData == null || wavData.length < WAV_MIN_HEADER_LENGTH
                || !matchesAscii(wavData, 0, "RIFF")
                || !matchesAscii(wavData, 8, "WAVE")) {
            throw new IllegalArgumentException("无效的 WAV 文件格式");
        }

        int offset = WAV_MIN_HEADER_LENGTH;
        while (offset + 8 <= wavData.length) {
            String chunkId = new String(wavData, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = readLittleEndianInt(wavData, offset + 4);
            int dataOffset = offset + 8;
            if (chunkSize < 0 || dataOffset + chunkSize > wavData.length) {
                throw new IllegalArgumentException("无效的 WAV chunk 大小: " + chunkId);
            }
            if ("data".equals(chunkId)) {
                return new WavLayout(dataOffset, chunkSize, offset + 4);
            }
            offset = dataOffset + chunkSize + (chunkSize % 2);
        }

        throw new IllegalArgumentException("WAV 文件缺少 data chunk");
    }

    private boolean matchesAscii(byte[] data, int offset, String expected) {
        if (data.length < offset + expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (data[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private void writeLittleEndianInt(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private record WavLayout(int dataOffset, int dataSize, int dataSizeOffset) {
    }
}
