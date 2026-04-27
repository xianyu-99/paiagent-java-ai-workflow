package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 条件分支节点执行器。
 */
@Component
public class ConditionNodeExecutor implements NodeExecutor {

    private static final String NODE_OUTPUTS_CONTEXT_KEY = "__nodeOutputs__";
    private static final String EXECUTION_USER_ID_CONTEXT_KEY = "__executionUserId__";
    private static final String EXECUTION_ADMIN_CONTEXT_KEY = "__executionAdmin__";

    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Map<String, Object> data = node.getData() == null ? Map.of() : node.getData();

        Object leftValue = resolveLeftValue(data, input);
        String operator = getString(data, "operator", "equals");
        String rightValue = getString(data, "rightValue", "");
        boolean caseSensitive = Boolean.TRUE.equals(data.get("caseSensitive"));

        boolean matched = evaluate(leftValue, operator, rightValue, caseSensitive);

        Map<String, Object> output = new HashMap<>(input);
        output.remove(NODE_OUTPUTS_CONTEXT_KEY);
        output.remove(EXECUTION_USER_ID_CONTEXT_KEY);
        output.remove(EXECUTION_ADMIN_CONTEXT_KEY);
        output.put("conditionResult", matched);
        output.put("selectedBranch", matched ? "true" : "false");
        output.put("leftValue", leftValue == null ? null : String.valueOf(leftValue));
        output.put("operator", operator);
        output.put("rightValue", rightValue);
        output.put("output", matched ? "true" : "false");
        return output;
    }

    @Override
    public String getSupportedNodeType() {
        return "condition";
    }

    private Object resolveLeftValue(Map<String, Object> data, Map<String, Object> input) {
        String leftType = getString(data, "leftType", "reference");
        if ("input".equals(leftType)) {
            return data.get("leftValue");
        }

        String reference = getString(data, "leftReference", "");
        if (!reference.isBlank()) {
            Object value = resolveReference(reference, input);
            if (value != null) {
                return value;
            }
        }

        Object directValue = data.get("leftValue");
        if (directValue != null) {
            return directValue;
        }
        if (input.containsKey("output")) {
            return input.get("output");
        }
        return input.get("input");
    }

    private Object resolveReference(String reference, Map<String, Object> input) {
        if (!reference.contains(".")) {
            return input.get(reference);
        }

        String[] parts = reference.split("\\.");
        String nodeId = parts[0];
        String field = parts[parts.length - 1];

        Object nodeOutputsObject = input.get("__nodeOutputs__");
        if (nodeOutputsObject instanceof Map<?, ?> nodeOutputs) {
            Object nodeOutputObject = nodeOutputs.get(nodeId);
            if (nodeOutputObject instanceof Map<?, ?> nodeOutput) {
                return nodeOutput.get(field);
            }
        }

        if ("user_input".equals(field)) {
            return input.get("input");
        }
        return input.get(field);
    }

    private boolean evaluate(Object leftValue, String operator, String rightValue, boolean caseSensitive) {
        String left = leftValue == null ? "" : String.valueOf(leftValue);
        String right = rightValue == null ? "" : rightValue;

        String normalizedLeft = caseSensitive ? left : left.toLowerCase(Locale.ROOT);
        String normalizedRight = caseSensitive ? right : right.toLowerCase(Locale.ROOT);
        Integer numericCompare = compareNumber(left, right);

        return switch (operator) {
            case "not_equals" -> !normalizedLeft.equals(normalizedRight);
            case "contains" -> normalizedLeft.contains(normalizedRight);
            case "not_contains" -> !normalizedLeft.contains(normalizedRight);
            case "starts_with" -> normalizedLeft.startsWith(normalizedRight);
            case "ends_with" -> normalizedLeft.endsWith(normalizedRight);
            case "empty" -> left.isBlank();
            case "not_empty" -> !left.isBlank();
            case "gt" -> numericCompare != null && numericCompare > 0;
            case "gte" -> numericCompare != null && numericCompare >= 0;
            case "lt" -> numericCompare != null && numericCompare < 0;
            case "lte" -> numericCompare != null && numericCompare <= 0;
            default -> normalizedLeft.equals(normalizedRight);
        };
    }

    private Integer compareNumber(String left, String right) {
        try {
            return new BigDecimal(left.trim()).compareTo(new BigDecimal(right.trim()));
        } catch (Exception e) {
            return null;
        }
    }

    private String getString(Map<String, Object> data, String key, String defaultValue) {
        Object value = data.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }
}
