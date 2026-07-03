package com.paiagent.engine.executor.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.reference.WorkflowReferenceResolver;
import com.paiagent.service.MinioService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
@Component
public class MediaNodeExecutor implements NodeExecutor {

    @Autowired
    private MinioService minioService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .build();

    @Override
    public String getSupportedNodeType() {
        return "media";
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input, Consumer<ExecutionEvent> progressCallback) throws Exception {
        String prompt = extractInputPrompt(node, input);
        if (!StringUtils.hasText(prompt)) {
            throw new IllegalArgumentException("媒体生成节点的提示词 (prompt) 不能为空");
        }

        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        String provider = stringValue(data.getOrDefault("provider", "openai"));
        String model = stringValue(data.getOrDefault("model", "dall-e-3"));
        String apiKey = stringValue(data.get("apiKey"));
        String apiUrl = stringValue(data.getOrDefault("apiUrl", "https://api.openai.com/v1/images/generations"));
        String resolution = stringValue(data.getOrDefault("resolution", "1024x1024"));
        String mediaType = stringValue(data.getOrDefault("mediaType", "image"));

        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalArgumentException("API Key 不能为空，请在节点配置中设置");
        }

        log.info("Media 节点执行 - Provider: {}, Type: {}, Model: {}, Prompt: {}", provider, mediaType, model, prompt);

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                node.getId(),
                node.getType(),
                "正在调用 " + provider + " 生成 " + mediaType + "...",
                null
            ));
        }

        JSONObject requestBody = new JSONObject();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("n", 1);
        requestBody.put("size", resolution);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody.toJSONString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("媒体生成接口调用失败，HTTP 状态码: " 
                + response.statusCode() + ", 响应: " + response.body());
        }

        JSONObject resultJson = JSON.parseObject(response.body());
        String mediaSourceUrl = extractUrlFromResult(resultJson);

        if (!StringUtils.hasText(mediaSourceUrl)) {
            throw new RuntimeException("API 返回成功，但未找到有效的媒体 URL 字段。响应: " + response.body());
        }

        log.info("媒体生成成功，原始 URL: {}", mediaSourceUrl);

        if (progressCallback != null) {
            progressCallback.accept(ExecutionEvent.nodeProgress(
                node.getId(),
                node.getType(),
                "生成成功，正在下载并转存文件...",
                null
            ));
        }

        byte[] mediaBytes = downloadMedia(mediaSourceUrl);
        String extension = mediaType.equals("video") ? ".mp4" : ".png";
        String mimeType = mediaType.equals("video") ? "video/mp4" : "image/png";
        String fileName = mediaType + "_" + UUID.randomUUID() + extension;
        String objectName = "media/" + fileName;
        
        String minioUrl = minioService.uploadFromBytes(mediaBytes, objectName, mimeType);

        Map<String, Object> output = new HashMap<>();
        output.put("mediaUrl", minioUrl);
        output.put("mediaType", mediaType);
        output.put("output", minioUrl);

        log.info("媒体文件已成功转存到 MinIO: {}", minioUrl);

        return output;
    }

    private String extractInputPrompt(WorkflowNode node, Map<String, Object> input) {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();
        List<Map<String, Object>> inputParams = (List<Map<String, Object>>) data.get("inputParams");

        if (inputParams != null && !inputParams.isEmpty()) {
            for (Map<String, Object> param : inputParams) {
                String paramName = (String) param.get("name");
                String resolved = resolveInputParam(param, input);
                if ("prompt".equals(paramName) && StringUtils.hasText(resolved)) {
                    return resolved;
                }
            }
        }

        String prompt = stringValue(input.get("output"));
        if (StringUtils.hasText(prompt)) {
            return prompt;
        }

        return stringValue(input.get("input"));
    }

    private String resolveInputParam(Map<String, Object> param, Map<String, Object> input) {
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

    private String extractUrlFromResult(JSONObject json) {
        JSONArray dataArray = json.getJSONArray("data");
        if (dataArray != null && !dataArray.isEmpty()) {
            JSONObject dataObj = dataArray.getJSONObject(0);
            if (dataObj.containsKey("url")) {
                return dataObj.getString("url");
            }
        }
        if (json.containsKey("url")) return json.getString("url");
        if (json.containsKey("video_url")) return json.getString("video_url");
        if (json.containsKey("image_url")) return json.getString("image_url");
        return null;
    }

    private byte[] downloadMedia(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        int statusCode = conn.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("媒体下载失败，HTTP 状态码: " + statusCode);
        }

        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            int maxSize = 100 * 1024 * 1024;
            while ((bytesRead = is.read(buffer)) != -1) {
                if (baos.size() + bytesRead > maxSize) {
                    throw new IllegalStateException("媒体文件超过 100MB 限制");
                }
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
