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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        assertTrue(String.valueOf(response.getOutputData()).contains("TRUE_BRANCH"));
        assertEquals(3, response.getNodeResults().size());
    }

    @Test
    void shouldExecuteOnlyFalseBranchWhenConditionDoesNotMatch() {
        ExecutionResponse response = workflowEngine.execute(createConditionWorkflow(), "stop here");

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());
        assertTrue(String.valueOf(response.getOutputData()).contains("FALSE_BRANCH"));
        assertEquals(3, response.getNodeResults().size());
    }

    @Test
    void shouldExposeEnterpriseServiceDeskFinalOutputAsBusinessObject() {
        ExecutionResponse response = workflowEngine.execute(
                createEnterpriseServiceDeskWorkflow(),
                "我连不上公司 VPN，提示证书过期，怎么办？"
        );

        assertNotNull(response);
        assertEquals("SUCCESS", response.getStatus());

        Map<?, ?> outputEnvelope = assertInstanceOf(Map.class, response.getOutputData());
        Map<?, ?> businessPayload = assertInstanceOf(Map.class, outputEnvelope.get("output"));
        assertEquals("create_ticket", businessPayload.get("nextAction"));
        assertTrue(businessPayload.containsKey("answer"));
        assertTrue(businessPayload.containsKey("citations"));
    }

    private Workflow createConditionWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(101L);
        workflow.setName("condition-test-workflow");
        workflow.setEngineType("dag");
        workflow.setFlowData(JSON.toJSONString(buildConditionConfig()));
        return workflow;
    }

    private Workflow createEnterpriseServiceDeskWorkflow() {
        Workflow workflow = new Workflow();
        workflow.setId(102L);
        workflow.setName("enterprise-service-desk-object-output-test");
        workflow.setEngineType("dag");
        workflow.setFlowData(JSON.toJSONString(buildEnterpriseServiceDeskConfig()));
        return workflow;
    }

    private WorkflowConfig buildEnterpriseServiceDeskConfig() {
        List<WorkflowNode> nodes = new ArrayList<>();
        nodes.add(node("input-default", "input", Map.of("label", "输入", "type", "input")));

        Map<String, Object> businessPayload = new HashMap<>();
        businessPayload.put("answer", "Please create a support ticket.");
        businessPayload.put("citations", List.of("VPN SOP"));
        businessPayload.put("confidence", 0.42d);
        businessPayload.put("resolved", false);
        businessPayload.put("nextAction", "create_ticket");
        businessPayload.put("ticketSummary", "VPN certificate expired.");
        businessPayload.put("escalationReason", "Need manual certificate reset.");

        Map<String, Object> payloadData = new HashMap<>();
        payloadData.put("label", "Business payload");
        payloadData.put("type", "output");
        payloadData.put("outputParams", List.of(
                Map.of("name", "answerPayload", "type", "input", "value", businessPayload)
        ));
        payloadData.put("responseContent", "{{answerPayload}}");
        nodes.add(node("business-payload", "output", payloadData));

        Map<String, Object> outputData = new HashMap<>();
        outputData.put("label", "Output 业务对象");
        outputData.put("type", "output");
        outputData.put("outputParams", List.of(
                Map.of("name", "answerPayload", "type", "reference", "referenceNode", "business-payload.output")
        ));
        outputData.put("responseContent", "{{answerPayload}}");
        nodes.add(node("output-service-desk", "output", outputData));

        WorkflowConfig config = new WorkflowConfig();
        config.setNodes(nodes);
        config.setEdges(List.of(
                edge("e1", "input-default", "business-payload", null),
                edge("e2", "business-payload", "output-service-desk", null)
        ));
        return config;
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
