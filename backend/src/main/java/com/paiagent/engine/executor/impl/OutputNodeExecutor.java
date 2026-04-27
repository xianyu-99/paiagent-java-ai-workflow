package com.paiagent.engine.executor.impl;

import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.model.WorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出节点执行器
 */
@Slf4j
@Component
public class OutputNodeExecutor implements NodeExecutor {

    private static final String NODE_OUTPUTS_CONTEXT_KEY = "__nodeOutputs__";
    private static final String NODE_EXECUTION_COUNT_CONTEXT_KEY = "__nodeExecutionCount__";
    private static final String EXECUTION_USER_ID_CONTEXT_KEY = "__executionUserId__";
    private static final String EXECUTION_ADMIN_CONTEXT_KEY = "__executionAdmin__";
    
    @Override
    public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) {
        Map<String, Object> output = new HashMap<>();
        
        // 获取节点配置
        Map<String, Object> nodeData = node.getData();
        if (nodeData == null) {
            output.put("output", input.get("output") != null ? input.get("output") : input.get("input"));
            return output;
        }
        
        // 获取 responseContent 模板
        String responseContent = (String) nodeData.get("responseContent");
        if (responseContent == null || responseContent.isEmpty()) {
            output.put("output", input.get("output") != null ? input.get("output") : input.get("input"));
            return output;
        }
        
        log.debug("输出节点配置 - responseContent: {}", summarizeForLog(responseContent));
        log.debug("输出节点配置 - outputParams: {}", summarizeForLog(nodeData.get("outputParams")));
        log.debug("输出节点输入数据: {}", summarizeForLog(removeInternalContext(input)));
        
        // 获取 outputParams 配置
        List<Map<String, Object>> outputParams = (List<Map<String, Object>>) nodeData.get("outputParams");
        Map<String, String> paramValues = new HashMap<>();
        
        if (outputParams != null) {
            for (Map<String, Object> param : outputParams) {
                String paramName = (String) param.get("name");
                String paramType = (String) param.get("type");
                
                if ("input".equals(paramType)) {
                    // 直接输入的值
                    paramValues.put(paramName, (String) param.get("value"));
                } else if ("reference".equals(paramType)) {
                    // 引用其他节点的输出
                    String reference = (String) param.get("referenceNode");
                    log.debug("处理引用参数: {} -> {}", paramName, reference);
                    
                    if (reference != null && reference.contains(".")) {
                        String[] parts = reference.split("\\.");
                        String refNodeId = parts[0]; // 节点ID，例如 "qwen-1"
                        String refParamName = parts[parts.length - 1]; // 参数名，例如 "response"
                        
                        Object refValue = null;
                        
                        // 优先从 input 的上层 nodeOutputs 中查找（LangGraph 场景）
                        if (input.containsKey(NODE_OUTPUTS_CONTEXT_KEY)) {
                            @SuppressWarnings("unchecked")
                            Map<String, Map<String, Object>> nodeOutputs = 
                                (Map<String, Map<String, Object>>) input.get(NODE_OUTPUTS_CONTEXT_KEY);
                            
                            if (nodeOutputs != null && nodeOutputs.containsKey(refNodeId)) {
                                Map<String, Object> nodeOutput = nodeOutputs.get(refNodeId);
                                refValue = nodeOutput.get(refParamName);
                                log.debug("从 nodeOutputs 中找到引用 {}.{} 的值: {}", refNodeId, refParamName, summarizeForLog(refValue));
                            }
                        }
                        
                        // 如果找不到，尝试从 input 中获取值（DAG 引擎场景）
                        if (refValue == null) {
                            refValue = input.get(refParamName);
                        }
                        
                        // 如果找不到，尝试使用 "input" 作为 fallback（因为输入节点输出的是 input）
                        if (refValue == null && "user_input".equals(refParamName)) {
                            refValue = input.get("input");
                        }
                        
                        log.debug("引用参数 {} 的值: {}", refParamName, summarizeForLog(refValue));
                        
                        if (refValue != null) {
                            paramValues.put(paramName, refValue.toString());
                        }
                    }
                }
            }
        }
        
        log.debug("参数值映射: {}", summarizeForLog(paramValues));
        
        // 替换模板中的 {{参数名}}
        String result = responseContent;
        Pattern pattern = Pattern.compile("\\{\\{(.*?)\\}\\}");
        Matcher matcher = pattern.matcher(responseContent);
        
        while (matcher.find()) {
            String paramName = matcher.group(1).trim();
            String paramValue = paramValues.getOrDefault(paramName, "");
            result = result.replace("{{" + paramName + "}}", paramValue);
        }
        
        log.debug("输出节点最终结果: {}", summarizeForLog(result));
        output.put("output", result);
        return output;
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
