package com.paiagent.engine.execution;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 节点执行成功后的结果和可靠性元数据。
 */
@Data
@Builder
public class NodeExecutionOutcome {
    private Map<String, Object> output;
    private List<NodeExecutionAttempt> attempts;
    private int retryCount;
    private long timeoutMs;
}
