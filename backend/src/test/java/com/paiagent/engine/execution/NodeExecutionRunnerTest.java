package com.paiagent.engine.execution;

import com.paiagent.config.ExecutionProperties;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.engine.executor.NodeExecutor;
import com.paiagent.engine.executor.NodeExecutorFactory;
import com.paiagent.engine.model.WorkflowNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeExecutionRunnerTest {

    private NodeExecutionRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.shutdown();
        }
    }

    @Test
    void shouldReturnOutputAndAttemptMetadataWhenNodeSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        runner = runnerWith(testExecutor((node, input, callback) -> {
            calls.incrementAndGet();
            return Map.of("answer", "ok", "input", input.get("input"));
        }));

        NodeExecutionOutcome outcome = runner.execute(
                node(Map.of("retryCount", 2, "timeoutMs", 1000, "retryBackoffMs", 0)),
                Map.of("input", "hello"),
                null
        );

        assertEquals(1, calls.get());
        assertEquals(Map.of("answer", "ok", "input", "hello"), outcome.getOutput());
        assertEquals(1, outcome.getAttempts().size());
        assertEquals("SUCCESS", outcome.getAttempts().getFirst().getStatus());
        assertEquals(0, outcome.getRetryCount());
        assertEquals(1000, outcome.getTimeoutMs());
    }

    @Test
    void shouldRetryFailureAndReturnSuccessfulAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        runner = runnerWith(testExecutor((node, input, callback) -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("temporary failure");
            }
            return Map.of("answer", "recovered");
        }));

        NodeExecutionOutcome outcome = runner.execute(
                node(Map.of("retryCount", 1, "timeoutMs", 1000, "retryBackoffMs", 0)),
                Map.of("input", "hello"),
                null
        );

        assertEquals(2, calls.get());
        assertEquals(Map.of("answer", "recovered"), outcome.getOutput());
        assertEquals(2, outcome.getAttempts().size());
        assertEquals("FAILED", outcome.getAttempts().get(0).getStatus());
        assertEquals("IllegalStateException", outcome.getAttempts().get(0).getErrorType());
        assertEquals("temporary failure", outcome.getAttempts().get(0).getMessage());
        assertEquals("SUCCESS", outcome.getAttempts().get(1).getStatus());
        assertEquals(1, outcome.getRetryCount());
    }

    @Test
    void shouldThrowStructuredExceptionAfterRetryExhausted() {
        AtomicInteger calls = new AtomicInteger();
        runner = runnerWith(testExecutor((node, input, callback) -> {
            calls.incrementAndGet();
            throw new IllegalArgumentException("bad input");
        }));

        NodeExecutionException error = assertThrows(
                NodeExecutionException.class,
                () -> runner.execute(
                        node(Map.of("retryCount", 1, "timeoutMs", 1000, "retryBackoffMs", 0)),
                        Map.of("input", "hello"),
                        null
                )
        );

        assertEquals(2, calls.get());
        assertEquals("IllegalArgumentException", error.getErrorType());
        assertEquals(2, error.getAttempts().size());
        assertEquals(1, error.getRetryCount());
        assertEquals("FAILED", error.getAttempts().get(0).getStatus());
        assertEquals("bad input", error.getAttempts().get(0).getMessage());
        assertEquals("bad input", error.getAttempts().get(1).getMessage());
    }

    @Test
    void shouldClassifyTimeouts() {
        runner = runnerWith(testExecutor((node, input, callback) -> {
            Thread.sleep(5000);
            return Map.of("answer", "late");
        }));

        NodeExecutionException error = assertThrows(
                NodeExecutionException.class,
                () -> runner.execute(
                        node(Map.of("retryCount", 0, "timeoutMs", 1000, "retryBackoffMs", 0)),
                        Map.of("input", "hello"),
                        null
                )
        );

        assertEquals("TIMEOUT", error.getErrorType());
        assertEquals(1, error.getAttempts().size());
        assertEquals("FAILED", error.getAttempts().getFirst().getStatus());
        assertEquals("TIMEOUT", error.getAttempts().getFirst().getErrorType());
        assertEquals(0, error.getRetryCount());
    }

    private NodeExecutionRunner runnerWith(NodeExecutor executor) {
        ExecutionProperties properties = new ExecutionProperties();
        properties.setNodeTimeoutMs(1000);
        properties.setNodeRetryCount(0);
        properties.setMaxNodeRetryCount(3);
        properties.setNodeRetryBackoffMs(0);
        properties.setNodeExecutorPoolSize(2);
        return new NodeExecutionRunner(new NodeExecutorFactory(List.of(executor)), properties);
    }

    private WorkflowNode node(Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId("node-1");
        node.setType("test");
        node.setData(new HashMap<>(data));
        return node;
    }

    private NodeExecutor testExecutor(ExecutorBehavior behavior) {
        return new NodeExecutor() {
            @Override
            public Map<String, Object> execute(WorkflowNode node, Map<String, Object> input) throws Exception {
                return behavior.execute(node, input, null);
            }

            @Override
            public Map<String, Object> execute(
                    WorkflowNode node,
                    Map<String, Object> input,
                    Consumer<ExecutionEvent> progressCallback
            ) throws Exception {
                return behavior.execute(node, input, progressCallback);
            }

            @Override
            public String getSupportedNodeType() {
                return "test";
            }
        };
    }

    @FunctionalInterface
    private interface ExecutorBehavior {
        Map<String, Object> execute(
                WorkflowNode node,
                Map<String, Object> input,
                Consumer<ExecutionEvent> progressCallback
        ) throws Exception;
    }
}
