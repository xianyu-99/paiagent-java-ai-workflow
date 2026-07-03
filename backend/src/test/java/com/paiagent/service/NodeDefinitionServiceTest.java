package com.paiagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeDefinitionServiceTest {

    private final NodeDefinitionService service = new NodeDefinitionService();

    @Test
    void shouldExposeAgentRuntimeConfigFields() {
        String schema = service.getByNodeType("agent").getConfigSchema();

        assertTrue(schema.contains("\"taskTemplate\""));
        assertTrue(schema.contains("\"enableExecutionMemory\""));
        assertTrue(schema.contains("\"memoryTopK\""));
        assertTrue(schema.contains("\"memoryMinScore\""));
    }

    @Test
    void shouldExposeRagRetrievalOnlyField() {
        String schema = service.getByNodeType("rag").getConfigSchema();

        assertTrue(schema.contains("\"retrievalOnly\""));
    }
}
