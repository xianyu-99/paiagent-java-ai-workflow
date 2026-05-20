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
import org.springframework.web.bind.annotation.RequestHeader;
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
        return Result.success(workflowPublishService.publishWorkflow(
                id,
                AuthContext.getUserId(servletRequest),
                AuthContext.isAdmin(servletRequest)
        ));
    }

    @Operation(summary = "Get workflow publish status")
    @GetMapping("/api/workflows/{id}/publish")
    public Result<WorkflowPublishResponse> getPublishStatus(@PathVariable Long id, HttpServletRequest servletRequest) {
        return Result.success(workflowPublishService.getPublishStatus(
                id,
                AuthContext.getUserId(servletRequest),
                AuthContext.isAdmin(servletRequest)
        ));
    }

    @Operation(summary = "Unpublish workflow")
    @DeleteMapping("/api/workflows/{id}/publish")
    public Result<WorkflowPublishResponse> unpublishWorkflow(@PathVariable Long id, HttpServletRequest servletRequest) {
        return Result.success(workflowPublishService.unpublishWorkflow(
                id,
                AuthContext.getUserId(servletRequest),
                AuthContext.isAdmin(servletRequest)
        ));
    }

    @Operation(summary = "Get published workflow info")
    @GetMapping("/api/published-workflows/{shareKey}")
    public Result<PublishedWorkflowInfoResponse> getPublishedWorkflow(@PathVariable String shareKey) {
        return Result.success(workflowPublishService.getPublicWorkflowInfo(shareKey));
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

    @Operation(summary = "Execute published workflow API")
    @PostMapping("/api/published-workflows/{shareKey}/execute-api")
    public Result<ExecutionResponse> executePublishedWorkflowApi(
            @PathVariable String shareKey,
            @RequestHeader(value = "X-PaiAgent-Api-Key", required = false) String apiAccessKey,
            @Valid @RequestBody ExecutionRequest request
    ) {
        try {
            return Result.success(workflowPublishService.executePublishedWorkflowApi(
                    shareKey,
                    request.getInputData(),
                    apiAccessKey
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (Exception e) {
            return Result.error("工作流执行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "Published workflow API usage hint")
    @GetMapping("/api/published-workflows/{shareKey}/execute")
    public Result<String> getPublishedWorkflowExecuteUsage(@PathVariable String shareKey) {
        return Result.error(
                405,
                "这是公开页面内部调用地址，不能直接在浏览器地址栏打开。请访问 /p/" + shareKey
                        + " 使用页面；正式 API 请 POST /api/published-workflows/" + shareKey
                        + "/execute-api，并在请求头传入 X-PaiAgent-Api-Key"
        );
    }

    @Operation(summary = "Published workflow protected API usage hint")
    @GetMapping("/api/published-workflows/{shareKey}/execute-api")
    public Result<String> getPublishedWorkflowApiUsage(@PathVariable String shareKey) {
        return Result.error(
                405,
                "这是 API POST 调用地址，不能直接在浏览器地址栏打开。请使用 POST JSON: {\"inputData\":\"你的输入\"}，并在请求头传入 X-PaiAgent-Api-Key"
        );
    }
}
