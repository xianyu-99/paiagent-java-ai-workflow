package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.entity.WorkflowTestCase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowTestHarnessEvaluatorTest {

    private final WorkflowTestHarnessEvaluator evaluator = new WorkflowTestHarnessEvaluator();

    @Test
    void passesWhenOutputMatchesKeywordsCitationAudioAndDuration() {
        WorkflowTestCase testCase = new WorkflowTestCase();
        testCase.setExpectedStatus("SUCCESS");
        testCase.setExpectedContains(JSON.toJSONString(List.of("Harness", "自动化")));
        testCase.setExpectedNotContains(JSON.toJSONString(List.of("失败")));
        testCase.setRequireCitation(true);
        testCase.setRequireAudio(true);
        testCase.setMaxDurationMs(5000);

        ExecutionResponse response = new ExecutionResponse();
        response.setStatus("SUCCESS");
        response.setDuration(1200);
        response.setOutputData(JSON.toJSONString(Map.of(
                "output", "Harness 可以做自动化验证",
                "audioUrl", "http://localhost/audio.wav",
                "citations", List.of(Map.of("ref", "来源1"))
        )));

        WorkflowTestHarnessEvaluator.Evaluation evaluation = evaluator.evaluate(testCase, response, null);

        assertEquals("PASSED", evaluation.status());
        assertTrue(evaluation.assertionResults().stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed"))));
    }

    @Test
    void failsWhenRequiredCitationIsMissing() {
        WorkflowTestCase testCase = new WorkflowTestCase();
        testCase.setExpectedStatus("SUCCESS");
        testCase.setExpectedContains("[]");
        testCase.setExpectedNotContains("[]");
        testCase.setRequireCitation(true);

        ExecutionResponse response = new ExecutionResponse();
        response.setStatus("SUCCESS");
        response.setOutputData("{\"output\":\"没有引用\"}");

        WorkflowTestHarnessEvaluator.Evaluation evaluation = evaluator.evaluate(testCase, response, null);

        assertEquals("FAILED", evaluation.status());
        assertTrue(evaluation.assertionResults().stream()
                .anyMatch(item -> "citation".equals(item.get("type")) && Boolean.FALSE.equals(item.get("passed"))));
    }
}
