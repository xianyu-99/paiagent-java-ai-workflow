package com.paiagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class WorkflowAsyncConfig {

    private final ExecutionProperties executionProperties;

    public WorkflowAsyncConfig(ExecutionProperties executionProperties) {
        this.executionProperties = executionProperties;
    }

    @Bean("workflowExecutionTaskExecutor")
    public ThreadPoolTaskExecutor workflowExecutionTaskExecutor() {
        int coreSize = Math.max(1, executionProperties.getWorkflowExecutorCoreSize());
        int maxSize = Math.max(coreSize, executionProperties.getWorkflowExecutorMaxSize());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(Math.max(0, executionProperties.getWorkflowExecutorQueueCapacity()));
        executor.setThreadNamePrefix("workflow-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    @Bean("ttsTaskExecutor")
    public ThreadPoolTaskExecutor ttsTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, executionProperties.getTtsExecutorPoolSize()));
        executor.setMaxPoolSize(Math.max(1, executionProperties.getTtsExecutorPoolSize()));
        executor.setQueueCapacity(Math.max(0, executionProperties.getTtsExecutorQueueCapacity()));
        executor.setThreadNamePrefix("tts-chunk-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
