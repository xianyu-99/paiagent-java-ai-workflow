package com.paiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流执行可靠性配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "paiagent.execution")
public class ExecutionProperties {

    /**
     * 单个节点默认超时时间，单位毫秒。
     */
    private long nodeTimeoutMs = 120000L;

    /**
     * 单个节点默认失败重试次数。默认 0，避免 LLM/TTS 产生额外调用成本。
     */
    private int nodeRetryCount = 0;

    /**
     * 单个节点允许配置的最大重试次数，防止误配置导致无限重试。
     */
    private int maxNodeRetryCount = 3;

    /**
     * 节点重试间隔，单位毫秒。
     */
    private long nodeRetryBackoffMs = 500L;

    /**
     * 节点执行线程池大小。
     */
    private int nodeExecutorPoolSize = 8;

    /**
     * 工作流异步执行线程池核心线程数。
     */
    private int workflowExecutorCoreSize = 4;

    /**
     * 工作流异步执行线程池最大线程数。
     */
    private int workflowExecutorMaxSize = 8;

    /**
     * 工作流异步执行队列容量。
     */
    private int workflowExecutorQueueCapacity = 100;

    /**
     * TTS 分片处理线程池大小。
     */
    private int ttsExecutorPoolSize = 4;

    /**
     * TTS 分片处理队列容量。
     */
    private int ttsExecutorQueueCapacity = 100;
}
