package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.paiagent.common.ForbiddenException;
import com.paiagent.dto.WorkflowRequest;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.entity.Workflow;
import com.paiagent.mapper.WorkflowMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class WorkflowService extends ServiceImpl<WorkflowMapper, Workflow> {

    private final ApiKeyCryptoService apiKeyCryptoService;

    public WorkflowService(ApiKeyCryptoService apiKeyCryptoService) {
        this.apiKeyCryptoService = apiKeyCryptoService;
    }

    public WorkflowResponse createWorkflow(WorkflowRequest request, Long ownerId) {
        if (ownerId == null) {
            throw new RuntimeException("用户未认证");
        }

        Workflow workflow = new Workflow();
        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setFlowData(apiKeyCryptoService.encryptApiKeysInJson(request.getFlowData()));
        workflow.setEngineType(request.getEngineType());
        workflow.setOwnerId(ownerId);

        this.save(workflow);

        return toResponse(workflow);
    }

    public WorkflowResponse updateWorkflow(Long id, WorkflowRequest request, Long userId, boolean admin) {
        Workflow workflow = getAccessibleWorkflow(id, userId, admin);
        String flowData = apiKeyCryptoService.preserveMissingApiKeysInJson(
                request.getFlowData(),
                workflow.getFlowData()
        );

        workflow.setName(request.getName());
        workflow.setDescription(request.getDescription());
        workflow.setFlowData(apiKeyCryptoService.encryptApiKeysInJson(flowData));
        workflow.setEngineType(request.getEngineType());

        this.updateById(workflow);

        return toResponse(workflow);
    }

    public void deleteWorkflow(Long id, Long userId, boolean admin) {
        Workflow workflow = getAccessibleWorkflow(id, userId, admin);
        this.removeById(workflow.getId());
    }

    public WorkflowResponse getWorkflowById(Long id, Long userId, boolean admin) {
        return toResponse(getAccessibleWorkflow(id, userId, admin));
    }

    public List<WorkflowResponse> listWorkflows(Long userId, boolean admin) {
        LambdaQueryWrapper<Workflow> wrapper = new LambdaQueryWrapper<>();
        if (!admin) {
            wrapper.eq(Workflow::getOwnerId, userId);
        }
        wrapper.orderByDesc(Workflow::getUpdatedAt);

        return this.list(wrapper).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public Workflow getAccessibleWorkflow(Long id, Long userId, boolean admin) {
        Workflow workflow = this.getById(id);
        if (workflow == null) {
            throw new RuntimeException("工作流不存在");
        }
        if (!admin && (workflow.getOwnerId() == null || !workflow.getOwnerId().equals(userId))) {
            throw new ForbiddenException("无权访问该工作流");
        }
        workflow.setFlowData(apiKeyCryptoService.decryptApiKeysInJson(workflow.getFlowData()));
        return workflow;
    }

    private WorkflowResponse toResponse(Workflow workflow) {
        WorkflowResponse response = new WorkflowResponse();
        BeanUtils.copyProperties(workflow, response);
        String decryptedFlowData = apiKeyCryptoService.decryptApiKeysInJson(response.getFlowData());
        response.setFlowData(apiKeyCryptoService.maskApiKeysInJson(decryptedFlowData));
        return response;
    }
}
