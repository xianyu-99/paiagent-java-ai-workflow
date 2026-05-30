package com.paiagent.engine.executor.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class EnterpriseSkillOutputContract {

    static final String SERVICE_DESK_ANSWER = "service-desk-answer";
    static final String SUPPORT_TICKET_ASSISTANT = "support-ticket-assistant";
    static final String RFP_SALES_ASSISTANT = "rfp-sales-assistant";

    static final String DIRECT_ANSWER = "direct_answer";
    static final String CREATE_TICKET = "create_ticket";
    static final String ESCALATE_HUMAN = "escalate_human";

    private static final Set<String> ENTERPRISE_SKILLS = Set.of(
            SERVICE_DESK_ANSWER,
            SUPPORT_TICKET_ASSISTANT,
            RFP_SALES_ASSISTANT
    );
    private static final Set<String> NEXT_ACTIONS = Set.of(
            DIRECT_ANSWER,
            CREATE_TICKET,
            ESCALATE_HUMAN
    );

    private EnterpriseSkillOutputContract() {
    }

    static Object normalize(String skillName, Object parsedContent, String rawContent) {
        if (!supports(skillName)) {
            return parsedContent;
        }

        if (!(parsedContent instanceof Map<?, ?> source)) {
            return fallback(rawContent);
        }

        Map<String, Object> normalized = copyStringKeyEntries(source);
        boolean complete = true;

        String answer = stringValue(source.get("answer"));
        if (answer == null) {
            answer = "";
            complete = false;
        }

        CitationValue citations = citationValue(source.get("citations"));
        if (!citations.valid()) {
            complete = false;
        }

        ConfidenceValue confidence = confidenceValue(source.get("confidence"));
        if (!confidence.valid()) {
            complete = false;
        }

        BooleanValue resolved = booleanValue(source.get("resolved"));
        if (!resolved.valid()) {
            complete = false;
        }

        ActionValue nextAction = actionValue(source.get("nextAction"));
        if (!nextAction.valid()) {
            complete = false;
        }

        String ticketSummary = stringValue(source.get("ticketSummary"));
        if (ticketSummary == null) {
            ticketSummary = "";
            complete = false;
        }

        String escalationReason = stringValue(source.get("escalationReason"));
        if (escalationReason == null) {
            escalationReason = "";
            complete = false;
        }

        boolean safeResolved = complete
                && resolved.value()
                && DIRECT_ANSWER.equals(nextAction.value());
        String safeNextAction = safeResolved || !DIRECT_ANSWER.equals(nextAction.value())
                ? nextAction.value()
                : CREATE_TICKET;

        normalized.put("answer", answer);
        normalized.put("citations", citations.value());
        normalized.put("confidence", confidence.value());
        normalized.put("resolved", safeResolved);
        normalized.put("nextAction", safeNextAction);
        normalized.put("ticketSummary", ticketSummary);
        normalized.put("escalationReason", escalationReason);
        return normalized;
    }

    static boolean supports(String skillName) {
        if (skillName == null) {
            return false;
        }
        return ENTERPRISE_SKILLS.contains(skillName.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, Object> fallback(String rawContent) {
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("answer", rawContent == null ? "" : rawContent.trim());
        fallback.put("citations", List.of());
        fallback.put("confidence", 0.0d);
        fallback.put("resolved", false);
        fallback.put("nextAction", CREATE_TICKET);
        fallback.put("ticketSummary", "");
        fallback.put("escalationReason", "");
        return fallback;
    }

    private static Map<String, Object> copyStringKeyEntries(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() instanceof String key) {
                copy.put(key, entry.getValue());
            }
        }
        return copy;
    }

    private static String stringValue(Object value) {
        if (!(value instanceof CharSequence text)) {
            return null;
        }
        return text.toString().trim();
    }

    private static CitationValue citationValue(Object value) {
        if (value instanceof Iterable<?> values) {
            List<String> citations = new ArrayList<>();
            for (Object citation : values) {
                String text = stringValue(citation);
                if (text != null && !text.isBlank()) {
                    citations.add(text);
                }
            }
            return new CitationValue(List.copyOf(citations), true);
        }

        String citation = stringValue(value);
        if (citation != null && !citation.isBlank()) {
            return new CitationValue(List.of(citation), true);
        }
        return new CitationValue(List.of(), false);
    }

    private static ConfidenceValue confidenceValue(Object value) {
        Double confidence = null;
        if (value instanceof Number number) {
            confidence = number.doubleValue();
        } else if (value instanceof CharSequence text) {
            try {
                confidence = Double.parseDouble(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return new ConfidenceValue(0.0d, false);
            }
        }

        if (confidence == null || !Double.isFinite(confidence) || confidence < 0.0d || confidence > 1.0d) {
            return new ConfidenceValue(0.0d, false);
        }
        return new ConfidenceValue(confidence, true);
    }

    private static BooleanValue booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return new BooleanValue(bool, true);
        }
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(normalized) || "false".equals(normalized)) {
                return new BooleanValue(Boolean.parseBoolean(normalized), true);
            }
        }
        return new BooleanValue(false, false);
    }

    private static ActionValue actionValue(Object value) {
        String nextAction = stringValue(value);
        if (nextAction == null) {
            return new ActionValue(CREATE_TICKET, false);
        }

        String normalized = nextAction.toLowerCase(Locale.ROOT);
        if (!NEXT_ACTIONS.contains(normalized)) {
            return new ActionValue(CREATE_TICKET, false);
        }
        return new ActionValue(normalized, true);
    }

    private record CitationValue(List<String> value, boolean valid) {
    }

    private record ConfidenceValue(double value, boolean valid) {
    }

    private record BooleanValue(boolean value, boolean valid) {
    }

    private record ActionValue(String value, boolean valid) {
    }
}
