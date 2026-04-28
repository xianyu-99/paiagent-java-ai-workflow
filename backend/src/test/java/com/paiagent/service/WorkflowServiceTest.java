package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.config.JwtSecretProvider;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.WorkflowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowServiceTest {

    @Test
    void shouldMaskNodeApiKeyInWorkflowResponseButDecryptForExecution() {
        ApiKeyCryptoService cryptoService = createCryptoService();
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        workflow.setName("TTS workflow");
        workflow.setOwnerId(2L);
        workflow.setEngineType("dag");
        workflow.setFlowData(cryptoService.encryptApiKeysInJson(JSON.toJSONString(Map.of(
                "nodes",
                List.of(Map.of(
                        "id", "tts-1",
                        "data", Map.of(
                                "type", "tts",
                                "apiKey", "sk-secret",
                                "model", "qwen3-tts-flash"
                        )
                )),
                "edges",
                List.of()
        ))));

        WorkflowService workflowService = createWorkflowService(cryptoService, workflow);

        WorkflowResponse response = workflowService.getWorkflowById(1L, 2L, false);
        JSONObject responseData = JSON.parseObject(response.getFlowData())
                .getJSONArray("nodes")
                .getJSONObject(0)
                .getJSONObject("data");

        assertEquals("", responseData.getString("apiKey"));
        assertTrue(responseData.getBooleanValue("apiKeyConfigured"));
        assertFalse(response.getFlowData().contains("sk-secret"));

        Workflow executable = workflowService.getWorkflowForExecution(1L, 2L, false);
        JSONObject executableData = JSON.parseObject(executable.getFlowData())
                .getJSONArray("nodes")
                .getJSONObject(0)
                .getJSONObject("data");
        assertEquals("sk-secret", executableData.getString("apiKey"));
    }

    private WorkflowService createWorkflowService(ApiKeyCryptoService cryptoService, Workflow workflow) {
        WorkflowMapper workflowMapper = mock(WorkflowMapper.class);
        when(workflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowService workflowService = new WorkflowService(cryptoService);
        ReflectionTestUtils.setField(workflowService, "baseMapper", workflowMapper);
        return workflowService;
    }

    private ApiKeyCryptoService createCryptoService() {
        JwtSecretProvider jwtSecretProvider = mock(JwtSecretProvider.class);
        when(jwtSecretProvider.getSecret()).thenReturn("test-secret-key-for-workflow-service");

        ApiKeyCryptoService service = new ApiKeyCryptoService(jwtSecretProvider);
        ReflectionTestUtils.setField(service, "configuredSecret", "test-api-key-encryption-secret");
        service.init();
        return service;
    }
}
