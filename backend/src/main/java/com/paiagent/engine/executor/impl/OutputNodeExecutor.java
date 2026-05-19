package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.engine.reference.WorkflowReferenceResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OutputNodeExecutor implements NodeExecutor {

    private static final String NODE_OUTPUTS_CONTEXT_KEY = "__nodeOutputs__";
    private static final String NODE_EXECUTION_COUNT_CONTEXT_KEY = "__nodeExecutionCount__";
    private static final String EXECUTION_USER_ID_CONTEXT_KEY = "__executionUserId__";
    private static final String EXECUTION_ADMIN_CONTEXT_KEY = "__executionAdmin__";
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(.*?)\\}\\}");

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Map<String, Object> output = new HashMap<>();
        Map<String, Object> nodeData = node.getData();
        if (nodeData == null) {
            output.put("output", defaultOutput(input));
            return output;
        }

        String responseContent = (String) nodeData.get("responseContent");
        if (responseContent == null || responseContent.isEmpty()) {
            output.put("output", defaultOutput(input));
            return output;
        }

        log.debug("Output node responseContent: {}", summarizeForLog(responseContent));
        log.debug("Output node outputParams: {}", summarizeForLog(nodeData.get("outputParams")));
        log.debug("Output node input: {}", summarizeForLog(removeInternalContext(input)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> outputParams = (List<Map<String, Object>>) nodeData.get("outputParams");
        Map<String, String> paramValues = buildParamValues(outputParams, input);
        log.debug("Output node parameter values: {}", summarizeForLog(paramValues));

        String result = replaceTemplateVariables(responseContent, paramValues);
        log.debug("Output node final result: {}", summarizeForLog(result));
        output.put("output", result);
        return output;
    }

    private Object defaultOutput(Map<String, Object> input) {
        return input.get("output") != null ? input.get("output") : input.get("input");
    }

    private Map<String, String> buildParamValues(
            List<Map<String, Object>> outputParams,
            Map<String, Object> input
    ) {
        Map<String, String> paramValues = new HashMap<>();
        if (outputParams == null) {
            return paramValues;
        }

        for (Map<String, Object> param : outputParams) {
            String paramName = (String) param.get("name");
            String paramType = (String) param.get("type");

            if ("input".equals(paramType)) {
                Object value = param.get("value");
                if (value != null) {
                    paramValues.put(paramName, value.toString());
                }
                continue;
            }

            if ("reference".equals(paramType)) {
                String reference = (String) param.get("referenceNode");
                Object value = WorkflowReferenceResolver.resolve(reference, input);
                if (value != null) {
                    paramValues.put(paramName, value.toString());
                }
            }
        }

        return paramValues;
    }

    private String replaceTemplateVariables(String template, Map<String, String> paramValues) {
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String paramName = matcher.group(1).trim();
            String paramValue = paramValues.getOrDefault(paramName, "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(paramValue));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private Map<String, Object> removeInternalContext(Map<String, Object> data) {
        Map<String, Object> cleanData = new HashMap<>();
        if (data != null) {
            cleanData.putAll(data);
        }
        cleanData.remove(NODE_OUTPUTS_CONTEXT_KEY);
        cleanData.remove(NODE_EXECUTION_COUNT_CONTEXT_KEY);
        cleanData.remove(EXECUTION_USER_ID_CONTEXT_KEY);
        cleanData.remove(EXECUTION_ADMIN_CONTEXT_KEY);
        return cleanData;
    }

    private String summarizeForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        int maxLength = 1000;
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...(truncated)";
    }

    @Override
    public String getSupportedNodeType() {
        return "output";
    }
}
