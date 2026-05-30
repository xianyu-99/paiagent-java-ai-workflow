package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.paiagent.common.ForbiddenException;
import com.paiagent.config.JwtSecretProvider;
import com.paiagent.dto.WorkflowRequest;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.WorkflowMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    @Test
    void shouldAllowSharedWorkflowReadAndExecutionButKeepItReadOnly() {
        ApiKeyCryptoService cryptoService = createCryptoService();
        Workflow workflow = new Workflow();
        workflow.setId(1L);
        workflow.setName("企业服务台助手");
        workflow.setFlowData("{\"nodes\":[],\"edges\":[]}");
        workflow.setEngineType("dag");

        WorkflowService workflowService = createWorkflowService(cryptoService, workflow);

        WorkflowResponse response = workflowService.getWorkflowById(1L, 2L, false);
        assertEquals("企业服务台助手", response.getName());
        assertEquals("{\"nodes\":[],\"edges\":[]}", workflowService.getWorkflowForExecution(1L, 2L, false).getFlowData());

        WorkflowRequest request = new WorkflowRequest();
        request.setName("修改默认模板");
        request.setDescription("default");
        request.setFlowData("{\"nodes\":[]}");
        request.setEngineType("dag");

        assertThrows(ForbiddenException.class, () -> workflowService.updateWorkflow(1L, request, 2L, false));
        assertThrows(ForbiddenException.class, () -> workflowService.deleteWorkflow(1L, 2L, false));
    }

    @Test
    void shouldRejectForeignAndDeletedWorkflowForRegularUser() {
        ApiKeyCryptoService cryptoService = createCryptoService();
        Workflow foreignWorkflow = new Workflow();
        foreignWorkflow.setId(1L);
        foreignWorkflow.setOwnerId(8L);
        foreignWorkflow.setFlowData("{\"nodes\":[]}");

        WorkflowService foreignWorkflowService = createWorkflowService(cryptoService, foreignWorkflow);
        assertThrows(ForbiddenException.class, () -> foreignWorkflowService.getWorkflowById(1L, 2L, false));

        Workflow deletedWorkflow = new Workflow();
        deletedWorkflow.setId(1L);
        deletedWorkflow.setFlowData("{\"nodes\":[]}");
        deletedWorkflow.setDeleted(1);

        WorkflowService deletedWorkflowService = createWorkflowService(cryptoService, deletedWorkflow);
        assertThrows(RuntimeException.class, () -> deletedWorkflowService.getWorkflowById(1L, 2L, false));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldIncludeSharedWorkflowsInRegularUserListQuery() {
        WorkflowMapper workflowMapper = mock(WorkflowMapper.class);
        when(workflowMapper.selectList(any())).thenReturn(List.of());

        WorkflowService workflowService = new WorkflowService(createCryptoService());
        ReflectionTestUtils.setField(workflowService, "baseMapper", workflowMapper);

        workflowService.listWorkflows(2L, false);

        ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(workflowMapper).selectList(wrapperCaptor.capture());
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Workflow.class);
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment().toUpperCase();
        assertTrue(sqlSegment.contains("OWNER_ID"));
        assertTrue(sqlSegment.contains("IS NULL"));
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
