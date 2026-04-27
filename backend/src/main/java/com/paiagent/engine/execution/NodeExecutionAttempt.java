package com.paiagent.engine.execution;

import lombok.Builder;
import lombok.Data;

/**
 * 单次节点执行尝试记录。
 */
@Data
@Builder
public class NodeExecutionAttempt {
    private int attempt;
    private String status;
    private String errorType;
    private String message;
    private long duration;
    private long timestamp;
}
