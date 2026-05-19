package com.paiagent.engine.llm;

import com.paiagent.engine.reference.WorkflowReferenceResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PromptTemplateService {

    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{(.*?)\\}\\}");

    public String processTemplate(
            String promptTemplate,
            List<Map<String, Object>> inputParams,
            Map<String, Object> runtimeInput
    ) {
        if (promptTemplate == null) {
            return "";
        }

        Map<String, String> paramValues = buildParamValues(inputParams, runtimeInput);
        log.debug("Prompt template parameter values: {}", paramValues);
        return replaceTemplateVariables(promptTemplate, paramValues);
    }

    private Map<String, String> buildParamValues(
            List<Map<String, Object>> inputParams,
            Map<String, Object> runtimeInput
    ) {
        Map<String, String> paramValues = new HashMap<>();

        if (inputParams == null) {
            return paramValues;
        }

        for (Map<String, Object> param : inputParams) {
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
                Object value = WorkflowReferenceResolver.resolve(reference, runtimeInput);
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
}
