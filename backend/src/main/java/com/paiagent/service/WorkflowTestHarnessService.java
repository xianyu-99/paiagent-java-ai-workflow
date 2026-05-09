package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.WorkflowTestCaseRequest;
import com.paiagent.dto.WorkflowTestCaseResponse;
import com.paiagent.dto.WorkflowTestResultResponse;
import com.paiagent.dto.WorkflowTestRunResponse;
import com.paiagent.engine.EngineSelector;
import com.paiagent.engine.WorkflowExecutor;
import com.paiagent.engine.execution.WorkflowExecutionContextHolder;
import com.paiagent.entity.Workflow;
import com.paiagent.entity.WorkflowTestCase;
import com.paiagent.entity.WorkflowTestResult;
import com.paiagent.entity.WorkflowTestRun;
import com.paiagent.mapper.WorkflowTestCaseMapper;
import com.paiagent.mapper.WorkflowTestResultMapper;
import com.paiagent.mapper.WorkflowTestRunMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class WorkflowTestHarnessService {

    private final WorkflowService workflowService;
    private final EngineSelector engineSelector;
    private final WorkflowTestCaseMapper testCaseMapper;
    private final WorkflowTestRunMapper testRunMapper;
    private final WorkflowTestResultMapper testResultMapper;
    private final WorkflowTestHarnessEvaluator evaluator;

    public WorkflowTestHarnessService(WorkflowService workflowService,
                                      EngineSelector engineSelector,
                                      WorkflowTestCaseMapper testCaseMapper,
                                      WorkflowTestRunMapper testRunMapper,
                                      WorkflowTestResultMapper testResultMapper,
                                      WorkflowTestHarnessEvaluator evaluator) {
        this.workflowService = workflowService;
        this.engineSelector = engineSelector;
        this.testCaseMapper = testCaseMapper;
        this.testRunMapper = testRunMapper;
        this.testResultMapper = testResultMapper;
        this.evaluator = evaluator;
    }

    public List<WorkflowTestCaseResponse> listTestCases(Long workflowId, Long userId, boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        return testCaseMapper.selectList(new LambdaQueryWrapper<WorkflowTestCase>()
                        .eq(WorkflowTestCase::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTestCase::getId))
                .stream()
                .map(this::toCaseResponse)
                .toList();
    }

    @Transactional
    public WorkflowTestCaseResponse createTestCase(Long workflowId,
                                                   WorkflowTestCaseRequest request,
                                                   Long userId,
                                                   boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        WorkflowTestCase testCase = new WorkflowTestCase();
        testCase.setWorkflowId(workflowId);
        fillCase(testCase, request);
        testCaseMapper.insert(testCase);
        return toCaseResponse(testCase);
    }

    @Transactional
    public WorkflowTestCaseResponse updateTestCase(Long workflowId,
                                                   Long caseId,
                                                   WorkflowTestCaseRequest request,
                                                   Long userId,
                                                   boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        WorkflowTestCase testCase = getWorkflowTestCase(workflowId, caseId);
        fillCase(testCase, request);
        testCaseMapper.updateById(testCase);
        return toCaseResponse(testCase);
    }

    @Transactional
    public void deleteTestCase(Long workflowId, Long caseId, Long userId, boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        getWorkflowTestCase(workflowId, caseId);
        testCaseMapper.deleteById(caseId);
    }

    public WorkflowTestRunResponse runTestCases(Long workflowId, Long userId, boolean admin) {
        Workflow workflow = workflowService.getWorkflowForExecution(workflowId, userId, admin);
        List<WorkflowTestCase> testCases = testCaseMapper.selectList(new LambdaQueryWrapper<WorkflowTestCase>()
                .eq(WorkflowTestCase::getWorkflowId, workflowId)
                .eq(WorkflowTestCase::getEnabled, true)
                .orderByAsc(WorkflowTestCase::getId));
        if (testCases.isEmpty()) {
            throw new RuntimeException("没有可运行的测试用例");
        }

        long runStart = System.currentTimeMillis();
        WorkflowTestRun run = new WorkflowTestRun();
        run.setWorkflowId(workflowId);
        run.setStatus("RUNNING");
        run.setTotalCount(testCases.size());
        run.setPassedCount(0);
        run.setFailedCount(0);
        run.setDuration(0);
        run.setCreatedBy(userId);
        testRunMapper.insert(run);

        int passedCount = 0;
        int failedCount = 0;
        WorkflowExecutor executor = engineSelector.selectEngine(workflow);

        for (WorkflowTestCase testCase : testCases) {
            ExecutionResponse response = null;
            Exception error = null;
            WorkflowExecutionContextHolder.set(userId, admin);
            try {
                response = executor.execute(workflow, testCase.getInputData());
            } catch (Exception e) {
                error = e;
            } finally {
                WorkflowExecutionContextHolder.clear();
            }

            WorkflowTestHarnessEvaluator.Evaluation evaluation = evaluator.evaluate(testCase, response, error);
            if ("PASSED".equals(evaluation.status())) {
                passedCount++;
            } else {
                failedCount++;
            }
            saveResult(run.getId(), testCase, response, evaluation);
        }

        run.setPassedCount(passedCount);
        run.setFailedCount(failedCount);
        run.setStatus(failedCount == 0 ? "PASSED" : "FAILED");
        run.setDuration((int) (System.currentTimeMillis() - runStart));
        testRunMapper.updateById(run);
        return getTestRun(workflowId, run.getId(), userId, admin);
    }

    public List<WorkflowTestRunResponse> listTestRuns(Long workflowId, Long userId, boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        return testRunMapper.selectList(new LambdaQueryWrapper<WorkflowTestRun>()
                        .eq(WorkflowTestRun::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTestRun::getCreatedAt)
                        .last("LIMIT 20"))
                .stream()
                .map(run -> toRunResponse(run, false))
                .toList();
    }

    public WorkflowTestRunResponse getTestRun(Long workflowId, Long runId, Long userId, boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        WorkflowTestRun run = testRunMapper.selectById(runId);
        if (run == null || !workflowId.equals(run.getWorkflowId())) {
            throw new RuntimeException("测试运行记录不存在");
        }
        return toRunResponse(run, true);
    }

    private void fillCase(WorkflowTestCase testCase, WorkflowTestCaseRequest request) {
        testCase.setName(request.getName().trim());
        testCase.setInputData(request.getInputData());
        testCase.setExpectedContains(JSON.toJSONString(request.getExpectedContains() == null ? List.of() : request.getExpectedContains()));
        testCase.setExpectedNotContains(JSON.toJSONString(request.getExpectedNotContains() == null ? List.of() : request.getExpectedNotContains()));
        testCase.setExpectedStatus(StringUtils.hasText(request.getExpectedStatus()) ? request.getExpectedStatus().trim() : "SUCCESS");
        testCase.setRequireCitation(Boolean.TRUE.equals(request.getRequireCitation()));
        testCase.setRequireAudio(Boolean.TRUE.equals(request.getRequireAudio()));
        testCase.setMaxDurationMs(request.getMaxDurationMs());
        testCase.setEnabled(request.getEnabled() == null || Boolean.TRUE.equals(request.getEnabled()));
    }

    private WorkflowTestCase getWorkflowTestCase(Long workflowId, Long caseId) {
        WorkflowTestCase testCase = testCaseMapper.selectById(caseId);
        if (testCase == null || !workflowId.equals(testCase.getWorkflowId())) {
            throw new RuntimeException("测试用例不存在");
        }
        return testCase;
    }

    private void saveResult(Long runId,
                            WorkflowTestCase testCase,
                            ExecutionResponse response,
                            WorkflowTestHarnessEvaluator.Evaluation evaluation) {
        WorkflowTestResult result = new WorkflowTestResult();
        result.setRunId(runId);
        result.setCaseId(testCase.getId());
        result.setCaseName(testCase.getName());
        result.setStatus(evaluation.status());
        result.setActualOutput(truncate(evaluation.actualOutput(), 20000));
        result.setAssertionResults(JSON.toJSONString(evaluation.assertionResults()));
        result.setExecutionId(response == null ? null : response.getExecutionId());
        result.setDuration(response == null ? null : response.getDuration());
        result.setErrorMessage(evaluation.errorMessage());
        testResultMapper.insert(result);
    }

    private WorkflowTestCaseResponse toCaseResponse(WorkflowTestCase testCase) {
        WorkflowTestCaseResponse response = new WorkflowTestCaseResponse();
        response.setId(testCase.getId());
        response.setWorkflowId(testCase.getWorkflowId());
        response.setName(testCase.getName());
        response.setInputData(testCase.getInputData());
        response.setExpectedContains(evaluator.parseStringList(testCase.getExpectedContains()));
        response.setExpectedNotContains(evaluator.parseStringList(testCase.getExpectedNotContains()));
        response.setExpectedStatus(testCase.getExpectedStatus());
        response.setRequireCitation(testCase.getRequireCitation());
        response.setRequireAudio(testCase.getRequireAudio());
        response.setMaxDurationMs(testCase.getMaxDurationMs());
        response.setEnabled(testCase.getEnabled());
        response.setCreatedAt(testCase.getCreatedAt());
        response.setUpdatedAt(testCase.getUpdatedAt());
        return response;
    }

    private WorkflowTestRunResponse toRunResponse(WorkflowTestRun run, boolean includeResults) {
        WorkflowTestRunResponse response = new WorkflowTestRunResponse();
        response.setId(run.getId());
        response.setWorkflowId(run.getWorkflowId());
        response.setStatus(run.getStatus());
        response.setTotalCount(run.getTotalCount());
        response.setPassedCount(run.getPassedCount());
        response.setFailedCount(run.getFailedCount());
        response.setDuration(run.getDuration());
        response.setCreatedBy(run.getCreatedBy());
        response.setCreatedAt(run.getCreatedAt());
        response.setUpdatedAt(run.getUpdatedAt());
        if (includeResults) {
            response.setResults(testResultMapper.selectList(new LambdaQueryWrapper<WorkflowTestResult>()
                            .eq(WorkflowTestResult::getRunId, run.getId())
                            .orderByAsc(WorkflowTestResult::getId))
                    .stream()
                    .map(this::toResultResponse)
                    .toList());
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private WorkflowTestResultResponse toResultResponse(WorkflowTestResult result) {
        WorkflowTestResultResponse response = new WorkflowTestResultResponse();
        response.setId(result.getId());
        response.setRunId(result.getRunId());
        response.setCaseId(result.getCaseId());
        response.setCaseName(result.getCaseName());
        response.setStatus(result.getStatus());
        response.setActualOutput(result.getActualOutput());
        response.setExecutionId(result.getExecutionId());
        response.setDuration(result.getDuration());
        response.setErrorMessage(result.getErrorMessage());
        response.setCreatedAt(result.getCreatedAt());
        try {
            response.setAssertionResults(JSON.parseObject(result.getAssertionResults(), List.class));
        } catch (Exception e) {
            response.setAssertionResults(List.of(Map.of("type", "parse", "passed", false, "message", "断言结果解析失败")));
        }
        return response;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated)";
    }
}
