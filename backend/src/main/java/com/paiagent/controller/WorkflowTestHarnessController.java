package com.paiagent.controller;

import com.paiagent.common.AuthContext;
import com.paiagent.common.ForbiddenException;
import com.paiagent.common.Result;
import com.paiagent.dto.WorkflowTestCaseRequest;
import com.paiagent.dto.WorkflowTestCaseResponse;
import com.paiagent.dto.WorkflowTestRunResponse;
import com.paiagent.service.WorkflowTestHarnessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Workflow Test Harness")
@RestController
@RequestMapping("/api/workflows/{workflowId}/harness")
public class WorkflowTestHarnessController {

    private final WorkflowTestHarnessService workflowTestHarnessService;

    public WorkflowTestHarnessController(WorkflowTestHarnessService workflowTestHarnessService) {
        this.workflowTestHarnessService = workflowTestHarnessService;
    }

    @Operation(summary = "List workflow test cases")
    @GetMapping("/test-cases")
    public Result<List<WorkflowTestCaseResponse>> listTestCases(@PathVariable Long workflowId,
                                                                HttpServletRequest request) {
        try {
            return Result.success(workflowTestHarnessService.listTestCases(
                    workflowId,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Create workflow test case")
    @PostMapping("/test-cases")
    public Result<WorkflowTestCaseResponse> createTestCase(@PathVariable Long workflowId,
                                                           @Valid @RequestBody WorkflowTestCaseRequest body,
                                                           HttpServletRequest request) {
        try {
            return Result.success(workflowTestHarnessService.createTestCase(
                    workflowId,
                    body,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Update workflow test case")
    @PutMapping("/test-cases/{caseId}")
    public Result<WorkflowTestCaseResponse> updateTestCase(@PathVariable Long workflowId,
                                                           @PathVariable Long caseId,
                                                           @Valid @RequestBody WorkflowTestCaseRequest body,
                                                           HttpServletRequest request) {
        try {
            return Result.success(workflowTestHarnessService.updateTestCase(
                    workflowId,
                    caseId,
                    body,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Delete workflow test case")
    @DeleteMapping("/test-cases/{caseId}")
    public Result<Void> deleteTestCase(@PathVariable Long workflowId,
                                       @PathVariable Long caseId,
                                       HttpServletRequest request) {
        try {
            workflowTestHarnessService.deleteTestCase(
                    workflowId,
                    caseId,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            );
            return Result.success();
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Run workflow test cases")
    @PostMapping("/test-runs")
    public Result<WorkflowTestRunResponse> runTestCases(@PathVariable Long workflowId,
                                                        HttpServletRequest request) {
        try {
            return Result.success(workflowTestHarnessService.runTestCases(
                    workflowId,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "List workflow test runs")
    @GetMapping("/test-runs")
    public Result<List<WorkflowTestRunResponse>> listTestRuns(@PathVariable Long workflowId,
                                                              HttpServletRequest request) {
        try {
            return Result.success(workflowTestHarnessService.listTestRuns(
                    workflowId,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }

    @Operation(summary = "Get workflow test run")
    @GetMapping("/test-runs/{runId}")
    public Result<WorkflowTestRunResponse> getTestRun(@PathVariable Long workflowId,
                                                      @PathVariable Long runId,
                                                      HttpServletRequest request) {
        try {
            return Result.success(workflowTestHarnessService.getTestRun(
                    workflowId,
                    runId,
                    AuthContext.getUserId(request),
                    AuthContext.isAdmin(request)
            ));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage());
        }
    }
}
