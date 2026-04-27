package com.paiagent.engine.execution;

import lombok.Builder;
import lombok.Data;

/**
 * 单个节点的执行策略。
 */
@Data
@Builder
public class NodeExecutionPolicy {
    private long timeoutMs;
    private int retryCount;
    private long retryBackoffMs;

    public int getMaxAttempts() {
        return retryCount + 1;
    }
}
