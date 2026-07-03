package com.paiagent.engine.executor.impl;

import org.springframework.stereotype.Component;

/**
 * 通义千问节点执行器。
 *
 * @deprecated 新流程请使用 node_type=llm 并在节点配置里选择 provider=qwen。
 */
@Deprecated
@Component
public class QwenNodeExecutor extends AbstractLLMNodeExecutor {

    @Override
    protected String getNodeType() {
        return "qwen";
    }
}
