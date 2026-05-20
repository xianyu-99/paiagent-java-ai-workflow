package com.paiagent.controller;

import com.paiagent.common.AuthContext;
import com.paiagent.common.Result;
import com.paiagent.dto.WorkflowRequest;
import com.paiagent.dto.WorkflowResponse;
import com.paiagent.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "工作流管理接口")
@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    @Autowired
    private WorkflowService workflowService;

    @Operation(summary = "创建工作流")
    @PostMapping
    public Result<WorkflowResponse> createWorkflow(@Valid @RequestBody WorkflowRequest request, HttpServletRequest servletRequest) {
        WorkflowResponse response = workflowService.createWorkflow(request, AuthContext.getUserId(servletRequest));
        return Result.success(response);
    }

    @Operation(summary = "更新工作流")
    @PutMapping("/{id}")
    public Result<WorkflowResponse> updateWorkflow(@PathVariable Long id, @Valid @RequestBody WorkflowRequest request, HttpServletRequest servletRequest) {
        WorkflowResponse response = workflowService.updateWorkflow(
                id,
                request,
                AuthContext.getUserId(servletRequest),
                AuthContext.isAdmin(servletRequest)
        );
        return Result.success(response);
    }

    @Operation(summary = "删除工作流")
    @DeleteMapping("/{id}")
    public Result<Void> deleteWorkflow(@PathVariable Long id, HttpServletRequest servletRequest) {
        workflowService.deleteWorkflow(id, AuthContext.getUserId(servletRequest), AuthContext.isAdmin(servletRequest));
        return Result.success();
    }

    @Operation(summary = "获取工作流详情")
    @GetMapping("/{id}")
    public Result<WorkflowResponse> getWorkflow(@PathVariable Long id, HttpServletRequest servletRequest) {
        WorkflowResponse response = workflowService.getWorkflowById(
                id,
                AuthContext.getUserId(servletRequest),
                AuthContext.isAdmin(servletRequest)
        );
        return Result.success(response);
    }

    @Operation(summary = "查询工作流列表")
    @GetMapping
    public Result<List<WorkflowResponse>> listWorkflows(HttpServletRequest servletRequest) {
        List<WorkflowResponse> list = workflowService.listWorkflows(
                AuthContext.getUserId(servletRequest),
                AuthContext.isAdmin(servletRequest)
        );
        return Result.success(list);
    }
}
