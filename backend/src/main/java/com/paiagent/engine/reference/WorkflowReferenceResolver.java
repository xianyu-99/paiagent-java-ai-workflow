package com.paiagent.engine.reference;

import java.util.Map;

public final class WorkflowReferenceResolver {

    public static final String NODE_OUTPUTS_CONTEXT_KEY = "__nodeOutputs__";

    private WorkflowReferenceResolver() {
    }

    public static Object resolve(String reference, Map<String, Object> runtimeInput) {
        if (isBlank(reference) || runtimeInput == null) {
            return null;
        }

        if (!reference.contains(".")) {
            return runtimeInput.get(reference);
        }

        String[] parts = reference.split("\\.", 2);
        String nodeId = parts[0];
        String fieldPath = parts.length > 1 ? parts[1] : "";

        Object value = resolveFromNodeOutputs(nodeId, fieldPath, runtimeInput);
        if (value != null) {
            return value;
        }

        value = resolvePath(runtimeInput, fieldPath);
        if (value != null) {
            return value;
        }

        String lastField = lastSegment(fieldPath);
        value = runtimeInput.get(lastField);
        if (value != null) {
            return value;
        }

        if ("user_input".equals(lastField)) {
            return runtimeInput.get("input");
        }

        return null;
    }

    private static Object resolveFromNodeOutputs(
            String nodeId,
            String fieldPath,
            Map<String, Object> runtimeInput
    ) {
        Object nodeOutputsObject = runtimeInput.get(NODE_OUTPUTS_CONTEXT_KEY);
        if (!(nodeOutputsObject instanceof Map<?, ?> nodeOutputs)) {
            return null;
        }

        Object nodeOutput = nodeOutputs.get(nodeId);
        return resolvePath(nodeOutput, fieldPath);
    }

    private static Object resolvePath(Object source, String fieldPath) {
        if (source == null || isBlank(fieldPath)) {
            return source;
        }

        Object current = source;
        for (String field : fieldPath.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(field);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static String lastSegment(String path) {
        if (path == null) {
            return "";
        }
        int index = path.lastIndexOf('.');
        return index >= 0 ? path.substring(index + 1) : path;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
