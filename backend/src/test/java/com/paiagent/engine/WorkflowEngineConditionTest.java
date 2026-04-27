package com.paiagent.engine;

import com.alibaba.fastjson2.JSON;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.model.WorkflowConfig;
import com.paiagent.engine.model.WorkflowEdge;
import com.paiagent.engine.model.WorkflowNode;
import com.paiagent.entity.Workflow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "paiagent.auth.jwt-secret=test-jwt-secret-key-for-condition-test-123456",
        "minio.endpoint=http://localhost:9000",
        "minio.accessKey=minioadmin",
        "minio.secretKey=minioadmin",
        "minio.bucketName=paiagent",
        "minio.publicUrl=http://localhost:9000",
        "spring.ai.openai.api-key=sk-test-placeholder",
        "spring.datasource.password=123456"
})
class WorkflowEngineConditionTest {

    @Autowired
    private WorkflowEngine workflowEngine;

    @Test
    void shouldExecuteOnlyTrueBranchWhenConditionMatches() {
        ExecutionResponse response = workflowEngine.execute(createConditionWorkflow(), "please go true branch");

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertTrue(response.getOutputData().contains("TRUE_BRANCH"));
        assertEquals(3, response.getNodeResults().size());
    }

    @Test
    void shouldExecuteOnlyFalseBranchWhenConditionDoesNotMatch() {
        ExecutionResponse response = workflowEngine.execute(createConditionWorkflow(), "stop here");

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertTrue(response.getOutputData().contains("FALSE_BRANCH"));
        assertEquals(3, response.getNodeResults().size());
    }

    private Workflow createConditionWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(101L);
        workflow.setName("condition-test-workflow");
        workflow.setEngineType("dag");
        workflow.setFlowData(JSON.toJSONString(buildConditionConfig()));
        return workflow;
    }

    private WorkflowConfig buildConditionConfig() {
        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(node("input-1", "input", Map.of("label", "输入", "type", "input")));

        Map<String, Object> conditionData = new HashMap<>();
        conditionData.put("label", "条件");
        conditionData.put("type", "condition");
        conditionData.put("leftType", "reference");
        conditionData.put("leftReference", "input-1.user_input");
        conditionData.put("operator", "contains");
        conditionData.put("rightValue", "go");
        conditionData.put("caseSensitive", false);
        nodes.add(node("condition-1", "condition", conditionData));

        nodes.add(node("output-true", "output", outputData("TRUE_BRANCH")));
        nodes.add(node("output-false", "output", outputData("FALSE_BRANCH")));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(List.of(
                edge("e1", "input-1", "condition-1", null),
                edge("e2", "condition-1", "output-true", "true"),
                edge("e3", "condition-1", "output-false", "false")
        ));
        return config;
    }

    private WorkflowNode node(String id, String type, Map<String, Object> data) {
        WorkflowNode node = new WorkflowNode();
        node.setId(id);
        node.setType(type);
        node.setData(data);
        return node;
    }

    private Map<String, Object> outputData(String responseContent) {
        Map<String, Object> data = new HashMap<>();
        data.put("label", responseContent);
        data.put("type", "output");
        data.put("outputParams", new ArrayList<>());
        data.put("responseContent", responseContent);
        return data;
    }

    private WorkflowEdge edge(String id, String source, String target, String sourceHandle) {
        WorkflowEdge edge = new WorkflowEdge();
        edge.setId(id);
        edge.setSource(source);
        edge.setTarget(target);
        edge.setSourceHandle(sourceHandle);
        return edge;
    }
}
