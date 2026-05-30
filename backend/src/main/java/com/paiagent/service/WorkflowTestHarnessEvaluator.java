package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.entity.WorkflowTestCase;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WorkflowTestHarnessEvaluator {

    public Evaluation evaluate(WorkflowTestCase testCase, ExecutionResponse response, Exception error) {
        List<Map<String, Object>> assertions = new ArrayList<>();
        String actualOutput = response == null ? "" : buildActualOutput(response);
        String expectedStatus = StringUtils.hasText(testCase.getExpectedStatus())
                ? testCase.getExpectedStatus()
                : "SUCCESS";
        String actualStatus = response == null ? "FAILED" : response.getStatus();

        addAssertion(
                assertions,
                "status",
                expectedStatus.equalsIgnoreCase(actualStatus),
                "期望状态 " + expectedStatus + "，实际状态 " + actualStatus
        );

        for (String keyword : parseStringList(testCase.getExpectedContains())) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            addAssertion(
                    assertions,
                    "contains",
                    actualOutput.contains(keyword),
                    "输出应包含关键词：" + keyword
            );
        }

        for (String keyword : parseStringList(testCase.getExpectedNotContains())) {
            if (!StringUtils.hasText(keyword)) {
                continue;
            }
            addAssertion(
                    assertions,
                    "not_contains",
                    !actualOutput.contains(keyword),
                    "输出不应包含关键词：" + keyword
            );
        }

        if (Boolean.TRUE.equals(testCase.getRequireCitation())) {
            addAssertion(assertions, "citation", hasField(response, "citations"), "RAG 输出应包含引用来源 citations");
        }

        if (Boolean.TRUE.equals(testCase.getRequireAudio())) {
            addAssertion(assertions, "audio", hasField(response, "audioUrl"), "TTS 输出应包含 audioUrl");
        }

        if (testCase.getMaxDurationMs() != null && testCase.getMaxDurationMs() > 0) {
            int duration = response == null || response.getDuration() == null ? Integer.MAX_VALUE : response.getDuration();
            addAssertion(
                    assertions,
                    "duration",
                    duration <= testCase.getMaxDurationMs(),
                    "执行耗时应不超过 " + testCase.getMaxDurationMs() + "ms，实际 " + duration + "ms"
            );
        }

        if (error != null) {
            addAssertion(assertions, "exception", false, "执行异常：" + error.getMessage());
        }

        boolean passed = assertions.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        String errorMessage = error != null ? error.getMessage() : response == null ? "工作流未返回执行结果" : response.getErrorMessage();
        return new Evaluation(passed ? "PASSED" : "FAILED", actualOutput, assertions, errorMessage);
    }

    public List<String> parseStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String buildActualOutput(ExecutionResponse response) {
        StringBuilder builder = new StringBuilder();
        append(builder, response.getOutputData());
        if (response.getNodeResults() != null) {
            for (ExecutionResponse.NodeResult nodeResult : response.getNodeResults()) {
                append(builder, nodeResult.getOutput());
                append(builder, nodeResult.getError());
            }
        }
        append(builder, response.getErrorMessage());
        return builder.toString();
    }

    private void append(StringBuilder builder, Object value) {
        String text = stringify(value);
        if (StringUtils.hasText(text)) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(text);
        }
    }

    private boolean hasField(ExecutionResponse response, String fieldName) {
        if (response == null) {
            return false;
        }
        List<Object> candidates = new ArrayList<>();
        candidates.add(response.getOutputData());
        if (response.getNodeResults() != null) {
            for (ExecutionResponse.NodeResult nodeResult : response.getNodeResults()) {
                candidates.add(nodeResult.getOutput());
            }
        }
        for (Object candidate : candidates) {
            if (jsonHasField(candidate, fieldName)) {
                return true;
            }
        }
        return false;
    }

    private boolean jsonHasField(Object candidate, String fieldName) {
        if (candidate == null) {
            return false;
        }
        try {
            if (candidate instanceof String json) {
                if (!StringUtils.hasText(json)) {
                    return false;
                }
                return objectHasField(JSON.parse(json), fieldName);
            }
            return objectHasField(JSON.toJSON(candidate), fieldName);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String stringify(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        return JSON.toJSONString(value);
    }

    private boolean objectHasField(Object value, String fieldName) {
        if (value instanceof JSONObject object) {
            Object fieldValue = object.get(fieldName);
            if (fieldValue instanceof JSONArray array) {
                return !array.isEmpty();
            }
            if (fieldValue instanceof String text) {
                return StringUtils.hasText(text);
            }
            if (fieldValue != null) {
                return true;
            }
            for (String key : object.keySet()) {
                if (objectHasField(object.get(key), fieldName)) {
                    return true;
                }
            }
        }
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                if (objectHasField(item, fieldName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addAssertion(List<Map<String, Object>> assertions, String type, boolean passed, String message) {
        Map<String, Object> assertion = new LinkedHashMap<>();
        assertion.put("type", type);
        assertion.put("passed", passed);
        assertion.put("message", message);
        assertions.add(assertion);
    }

    public record Evaluation(
            String status,
            String actualOutput,
            List<Map<String, Object>> assertionResults,
            String errorMessage
    ) {
    }
}
