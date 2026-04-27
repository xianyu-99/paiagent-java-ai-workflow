package com.paiagent.engine.execution;

import lombok.Getter;

import java.util.List;

/**
 * 节点在超时或重试耗尽后的统一异常。
 */
@Getter
public class NodeExecutionException extends Exception {
    private final String errorType;
    private final List<NodeExecutionAttempt> attempts;
    private final int retryCount;
    private final long timeoutMs;

    public NodeExecutionException(
            String message,
            String errorType,
            List<NodeExecutionAttempt> attempts,
            int retryCount,
            long timeoutMs,
            Throwable cause
    ) {
        super(message, cause);
        this.errorType = errorType;
        this.attempts = attempts;
        this.retryCount = retryCount;
        this.timeoutMs = timeoutMs;
    }
}
