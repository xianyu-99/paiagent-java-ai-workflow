package com.paiagent.engine.executor.impl;

import org.springframework.stereotype.Component;

/**
 * DeepSeek 节点执行器。
 *
 * @deprecated 新流程请使用 node_type=llm 并在节点配置里选择 provider=deepseek。
 */
@Deprecated
@Component
public class DeepSeekNodeExecutor extends AbstractLLMNodeExecutor {

    @Override
    protected String getNodeType() {
        return "deepseek";
    }
}
