package com.paiagent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.entity.LLMGlobalConfig;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.LLMGlobalConfigMapper;
import com.paiagent.mapper.WorkflowMapper;
import com.paiagent.service.ApiKeyCryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ApiKeyEncryptionMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyEncryptionMigrationRunner.class);

    private final ApiKeyCryptoService apiKeyCryptoService;
    private final LLMGlobalConfigMapper llmGlobalConfigMapper;
    private final WorkflowMapper workflowMapper;

    public ApiKeyEncryptionMigrationRunner(ApiKeyCryptoService apiKeyCryptoService,
                                           LLMGlobalConfigMapper llmGlobalConfigMapper,
                                           WorkflowMapper workflowMapper) {
        this.apiKeyCryptoService = apiKeyCryptoService;
        this.llmGlobalConfigMapper = llmGlobalConfigMapper;
        this.workflowMapper = workflowMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        migrateGlobalLlmConfigKeys();
        migrateWorkflowNodeKeys();
    }

    private void migrateGlobalLlmConfigKeys() {
        int migrated = 0;
        for (LLMGlobalConfig config : llmGlobalConfigMapper.selectList(new LambdaQueryWrapper<>())) {
            String apiKey = config.getApiKey();
            if (!StringUtils.hasText(apiKey) || apiKeyCryptoService.isEncrypted(apiKey)) {
                continue;
            }

            config.setApiKey(apiKeyCryptoService.encrypt(apiKey));
            llmGlobalConfigMapper.updateById(config);
            migrated++;
        }
        if (migrated > 0) {
            log.info("Encrypted {} legacy LLM API keys", migrated);
        }
    }

    private void migrateWorkflowNodeKeys() {
        int migrated = 0;
        for (Workflow workflow : workflowMapper.selectList(new LambdaQueryWrapper<>())) {
            String flowData = workflow.getFlowData();
            if (!StringUtils.hasText(flowData) || !flowData.contains("apiKey")) {
                continue;
            }

            String encryptedFlowData = apiKeyCryptoService.encryptApiKeysInJson(flowData);
            if (!flowData.equals(encryptedFlowData)) {
                workflow.setFlowData(encryptedFlowData);
                workflowMapper.updateById(workflow);
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("Encrypted apiKey fields in {} legacy workflows", migrated);
        }
    }
}
