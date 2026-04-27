package com.paiagent.engine.execution;

import com.paiagent.config.ExecutionProperties;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowNode;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 带超时和重试能力的节点执行器包装层。
 */
@Slf4j
@Component
public class NodeExecutionRunner {

    private final NodeExecutorFactory executorFactory;
    private final ExecutionProperties properties;
    private final ExecutorService executorService;

    public NodeExecutionRunner(NodeExecutorFactory executorFactory, ExecutionProperties properties) {
        this.executorFactory = executorFactory;
        this.properties = properties;
        this.executorService = Executors.newFixedThreadPool(
                Math.max(1, properties.getNodeExecutorPoolSize()),
                new NamedThreadFactory()
        );
    }

    public NodeExecutionOutcome execute(
            WorkflowNode node,
            Map<String, Object> input,
            Consumer<ExecutionEvent> eventCallback
    ) throws NodeExecutionException {
        NodeExecutionPolicy policy = resolvePolicy(node);
        List<NodeExecutionAttempt> attempts = new ArrayList<>();
        Throwable lastError = null;
        String lastErrorType = null;

        for (int attempt = 1; attempt <= policy.getMaxAttempts(); attempt++) {
            long attemptStart = System.currentTimeMillis();
            Future<Map<String, Object>> future = null;

            try {
                NodeExecutor executor = executorFactory.getExecutor(node.getType());
                Map<String, Object> attemptInput = new HashMap<>(input);
                future = executorService.submit(() -> executor.execute(node, attemptInput, eventCallback));

                Map<String, Object> output = future.get(policy.getTimeoutMs(), TimeUnit.MILLISECONDS);
                long duration = System.currentTimeMillis() - attemptStart;

                attempts.add(NodeExecutionAttempt.builder()
                        .attempt(attempt)
                        .status("SUCCESS")
                        .duration(duration)
                        .timestamp(System.currentTimeMillis())
                        .build());

                return NodeExecutionOutcome.builder()
                        .output(output == null ? new HashMap<>() : output)
                        .attempts(attempts)
                        .retryCount(attempt - 1)
                        .timeoutMs(policy.getTimeoutMs())
                        .build();
            } catch (TimeoutException e) {
                if (future != null) {
                    future.cancel(true);
                }
                lastError = e;
                lastErrorType = "TIMEOUT";
                addFailedAttempt(attempts, attempt, attemptStart, lastErrorType,
                        "节点执行超时，超过 " + policy.getTimeoutMs() + "ms");
            } catch (InterruptedException e) {
                if (future != null) {
                    future.cancel(true);
                }
                Thread.currentThread().interrupt();
                lastError = e;
                lastErrorType = "INTERRUPTED";
                addFailedAttempt(attempts, attempt, attemptStart, lastErrorType, "节点执行被中断");
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                lastError = cause;
                lastErrorType = classify(cause);
                addFailedAttempt(attempts, attempt, attemptStart, lastErrorType, safeMessage(cause));
            } catch (Exception e) {
                lastError = e;
                lastErrorType = classify(e);
                addFailedAttempt(attempts, attempt, attemptStart, lastErrorType, safeMessage(e));
            }

            if (attempt < policy.getMaxAttempts()) {
                log.warn("节点 [{}] 第 {} 次执行失败，准备重试: {}", node.getId(), attempt, safeMessage(lastError));
                if (eventCallback != null) {
                    eventCallback.accept(ExecutionEvent.nodeRetry(
                            node.getId(),
                            node.getType(),
                            attempt,
                            policy.getMaxAttempts(),
                            safeMessage(lastError),
                            policy.getRetryBackoffMs()
                    ));
                }
                sleepBackoff(policy.getRetryBackoffMs(), attempts, policy);
            }
        }

        String message = "节点 " + node.getId() + " 执行失败，已尝试 " + attempts.size() + " 次: " + safeMessage(lastError);
        throw new NodeExecutionException(
                message,
                lastErrorType == null ? "UNKNOWN" : lastErrorType,
                attempts,
                Math.max(0, attempts.size() - 1),
                policy.getTimeoutMs(),
                lastError
        );
    }

    private NodeExecutionPolicy resolvePolicy(WorkflowNode node) {
        Map<String, Object> data = node.getData();
        long timeoutMs = getLong(data, "timeoutMs", properties.getNodeTimeoutMs());
        int retryCount = getInt(data, "retryCount", getInt(data, "maxRetries", properties.getNodeRetryCount()));
        long retryBackoffMs = getLong(data, "retryBackoffMs", properties.getNodeRetryBackoffMs());

        timeoutMs = Math.max(1000L, timeoutMs);
        retryCount = Math.max(0, Math.min(retryCount, properties.getMaxNodeRetryCount()));
        retryBackoffMs = Math.max(0L, retryBackoffMs);

        return NodeExecutionPolicy.builder()
                .timeoutMs(timeoutMs)
                .retryCount(retryCount)
                .retryBackoffMs(retryBackoffMs)
                .build();
    }

    private long getLong(Map<String, Object> data, String key, long defaultValue) {
        Object value = data == null ? null : data.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private int getInt(Map<String, Object> data, String key, int defaultValue) {
        Object value = data == null ? null : data.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private void addFailedAttempt(
            List<NodeExecutionAttempt> attempts,
            int attempt,
            long attemptStart,
            String errorType,
            String message
    ) {
        attempts.add(NodeExecutionAttempt.builder()
                .attempt(attempt)
                .status("FAILED")
                .errorType(errorType)
                .message(message)
                .duration(System.currentTimeMillis() - attemptStart)
                .timestamp(System.currentTimeMillis())
                .build());
    }

    private String classify(Throwable error) {
        if (error instanceof TimeoutException) {
            return "TIMEOUT";
        }
        return error == null ? "UNKNOWN" : error.getClass().getSimpleName();
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private void sleepBackoff(
            long retryBackoffMs,
            List<NodeExecutionAttempt> attempts,
            NodeExecutionPolicy policy
    ) throws NodeExecutionException {
        if (retryBackoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryBackoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeExecutionException(
                    "节点重试等待被中断",
                    "INTERRUPTED",
                    attempts,
                    Math.max(0, attempts.size() - 1),
                    policy.getTimeoutMs(),
                    e
            );
        }
    }

    @PreDestroy
    public void shutdown() {
        executorService.shutdownNow();
    }

    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "workflow-node-exec-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
