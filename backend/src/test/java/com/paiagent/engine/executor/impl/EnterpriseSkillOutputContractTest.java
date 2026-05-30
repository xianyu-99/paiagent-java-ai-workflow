package com.paiagent.engine.executor.impl;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseSkillOutputContractTest {

    @Test
    void shouldKeepNonEnterpriseLlmOutputUntouched() {
        Map<String, Object> parsed = Map.of("answer", "free form");

        Object normalized = EnterpriseSkillOutputContract.normalize("ai-podcast", parsed, "{\"answer\":\"free form\"}");

        assertSame(parsed, normalized);
    }

    @Test
    void shouldKeepCompleteServiceDeskDecisionStructured() {
        Map<String, Object> parsed = new LinkedHashMap<>();
        parsed.put("answer", "Check the VPN certificate.");
        parsed.put("citations", List.of("VPN SOP"));
        parsed.put("confidence", "0.86");
        parsed.put("resolved", "true");
        parsed.put("nextAction", "DIRECT_ANSWER");
        parsed.put("ticketSummary", "");
        parsed.put("escalationReason", "");
        parsed.put("traceId", "keep-me");

        Map<?, ?> normalized = normalize(EnterpriseSkillOutputContract.SERVICE_DESK_ANSWER, parsed);

        assertEquals("Check the VPN certificate.", normalized.get("answer"));
        assertEquals(List.of("VPN SOP"), normalized.get("citations"));
        assertEquals(0.86d, normalized.get("confidence"));
        assertEquals(true, normalized.get("resolved"));
        assertEquals(EnterpriseSkillOutputContract.DIRECT_ANSWER, normalized.get("nextAction"));
        assertEquals("keep-me", normalized.get("traceId"));
    }

    @Test
    void shouldFallBackToTicketWhenEnterpriseContractIsIncompleteOrUnsafe() {
        Map<String, Object> parsed = Map.of(
                "answer", "Try the documented steps.",
                "confidence", 1.2d,
                "resolved", true,
                "nextAction", "close_case"
        );

        Map<?, ?> normalized = normalize(EnterpriseSkillOutputContract.SERVICE_DESK_ANSWER, parsed);

        assertEquals("Try the documented steps.", normalized.get("answer"));
        assertEquals(List.of(), normalized.get("citations"));
        assertEquals(0.0d, normalized.get("confidence"));
        assertEquals(false, normalized.get("resolved"));
        assertEquals(EnterpriseSkillOutputContract.CREATE_TICKET, normalized.get("nextAction"));
        assertEquals("", normalized.get("ticketSummary"));
        assertEquals("", normalized.get("escalationReason"));
    }

    @Test
    void shouldBuildSafeStructuredFallbackForRawEnterpriseOutput() {
        Map<?, ?> normalized = normalize(EnterpriseSkillOutputContract.SUPPORT_TICKET_ASSISTANT, "raw support reply");

        assertEquals("raw support reply", normalized.get("answer"));
        assertEquals(List.of(), normalized.get("citations"));
        assertEquals(0.0d, normalized.get("confidence"));
        assertEquals(false, normalized.get("resolved"));
        assertEquals(EnterpriseSkillOutputContract.CREATE_TICKET, normalized.get("nextAction"));
    }

    @Test
    void shouldCoverAllEnterpriseSkillsAndDowngradeContradictoryDirectAction() {
        assertTrue(EnterpriseSkillOutputContract.supports(EnterpriseSkillOutputContract.SERVICE_DESK_ANSWER));
        assertTrue(EnterpriseSkillOutputContract.supports(EnterpriseSkillOutputContract.SUPPORT_TICKET_ASSISTANT));
        assertTrue(EnterpriseSkillOutputContract.supports(EnterpriseSkillOutputContract.RFP_SALES_ASSISTANT));
        assertFalse(EnterpriseSkillOutputContract.supports("generic-assistant"));

        Map<?, ?> normalized = normalize(EnterpriseSkillOutputContract.RFP_SALES_ASSISTANT, Map.of(
                "answer", "Need customer scale details.",
                "citations", List.of("Product FAQ"),
                "confidence", 0.7d,
                "resolved", false,
                "nextAction", EnterpriseSkillOutputContract.DIRECT_ANSWER,
                "ticketSummary", "Collect customer scale details.",
                "escalationReason", ""
        ));

        assertEquals(false, normalized.get("resolved"));
        assertEquals(EnterpriseSkillOutputContract.CREATE_TICKET, normalized.get("nextAction"));
    }

    private Map<?, ?> normalize(String skillName, Object parsedContent) {
        return (Map<?, ?>) EnterpriseSkillOutputContract.normalize(skillName, parsedContent, String.valueOf(parsedContent));
    }
}
