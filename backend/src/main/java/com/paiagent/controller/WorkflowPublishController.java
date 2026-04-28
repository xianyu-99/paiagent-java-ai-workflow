package com.paiagent.controller;

import com.paiagent.common.AuthContext;
import com.paiagent.common.ForbiddenException;
import com.paiagent.common.Result;
import com.paiagent.dto.ExecutionRequest;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.PublishedWorkflowInfoResponse;
import com.paiagent.dto.WorkflowPublishResponse;
import com.paiagent.service.WorkflowPublishService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Workflow Publish")
@RestController
public class WorkflowPublishController {

    private final WorkflowPublishService workflowPublishService;

    public WorkflowPublishController(WorkflowPublishService workflowPublishService) {
        this.workflowPublishService = workflowPublishService;
    }

    @Operation(summary = "Publish workflow")
    @PostMapping("/api/workflows/{id}/publish")
    public Result<WorkflowPublishResponse> publishWorkflow(@PathVariable Long id, HttpServletRequest servletRequest) {
        try {
            return Result.success(workflowPublishService.publishWorkflow(
                    id,
                    AuthContext.getUserId(servletRequest),
                    AuthContext.isAdmin(servletRequest)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Get workflow publish status")
    @GetMapping("/api/workflows/{id}/publish")
    public Result<WorkflowPublishResponse> getPublishStatus(@PathVariable Long id, HttpServletRequest servletRequest) {
        try {
            return Result.success(workflowPublishService.getPublishStatus(
                    id,
                    AuthContext.getUserId(servletRequest),
                    AuthContext.isAdmin(servletRequest)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Unpublish workflow")
    @DeleteMapping("/api/workflows/{id}/publish")
    public Result<WorkflowPublishResponse> unpublishWorkflow(@PathVariable Long id, HttpServletRequest servletRequest) {
        try {
            return Result.success(workflowPublishService.unpublishWorkflow(
                    id,
                    AuthContext.getUserId(servletRequest),
                    AuthContext.isAdmin(servletRequest)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Get published workflow info")
    @GetMapping("/api/published-workflows/{shareKey}")
    public Result<PublishedWorkflowInfoResponse> getPublishedWorkflow(@PathVariable String shareKey) {
        try {
            return Result.success(workflowPublishService.getPublicWorkflowInfo(shareKey));
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Execute published workflow")
    @PostMapping("/api/published-workflows/{shareKey}/execute")
    public Result<ExecutionResponse> executePublishedWorkflow(
            @PathVariable String shareKey,
            @Valid @RequestBody ExecutionRequest request
    ) {
        try {
            return Result.success(workflowPublishService.executePublishedWorkflow(shareKey, request.getInputData()));
        } catch (Exception e) {
            return Result.error("工作流执行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "Published workflow API usage hint")
    @GetMapping("/api/published-workflows/{shareKey}/execute")
    public Result<String> getPublishedWorkflowExecuteUsage(@PathVariable String shareKey) {
        return Result.error(
                405,
                "这是 API POST 调用地址，不能直接在浏览器地址栏打开。请访问 /p/" + shareKey
                        + " 使用页面，或用 POST JSON 调用: {\"inputData\":\"你的输入\"}"
        );
    }
}
