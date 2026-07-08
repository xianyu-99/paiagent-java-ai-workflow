package com.paiagent.service.graph;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.config.RagGraphExtractionProperties;
import com.paiagent.engine.llm.ChatClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
public class LlmGraphSemanticExtractor implements GraphSemanticExtractor {

    private static final String SYSTEM_PROMPT = """
            You extract enterprise service-desk knowledge graph facts.
            Return only one JSON object, without markdown fences.
            Allowed entity types: PROCESS, ROLE, PERSON, DEPARTMENT, MATERIAL, SYSTEM, SLA, THRESHOLD, CONCEPT, DOCUMENT.
            Allowed relation types: requires_material, requires_approval_by, handled_by, responsible_for, depends_on, has_sla, has_threshold, impacts, causes, related_to.
            Every relation must include evidence copied exactly from the input chunk.
            Do not invent entities, relations, or evidence.
            JSON schema:
            {
              "entities": [
                {"name":"...", "type":"SYSTEM", "aliases":["..."], "confidence":0.82}
              ],
              "relations": [
                {"source":"...", "sourceType":"PERSON", "relationType":"responsible_for", "target":"...", "targetType":"SYSTEM", "evidence":"exact text from chunk", "confidence":0.78}
              ]
            }
            """;

    private final RagGraphExtractionProperties properties;
    private final ChatClientFactory chatClientFactory;

    public LlmGraphSemanticExtractor(RagGraphExtractionProperties properties,
                                     ChatClientFactory chatClientFactory) {
        this.properties = properties;
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public GraphExtractionResult extract(String content, List<GraphExtractionResult.EntityMention> seedEntities) {
        if (properties == null || !properties.isLlmEnabled() || !StringUtils.hasText(content)) {
            return empty();
        }
        try {
            ChatClient client = chatClientFactory.createClient(
                    properties.getLlmProvider(),
                    properties.getLlmBaseUrl(),
                    properties.getLlmApiKey(),
                    properties.getLlmModel(),
                    properties.getLlmTemperature()
            );
            ChatResponse response = client.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildUserPrompt(content, seedEntities))
                    .call()
                    .chatResponse();
            String raw = response.getResult().getOutput().getContent();
            return parseJsonResponse(raw);
        } catch (Exception e) {
            log.warn("LLM graph semantic extraction skipped: {}", e.getMessage());
            return empty();
        }
    }

    static GraphExtractionResult parseJsonResponse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return empty();
        }
        try {
            JSONObject object = JSON.parseObject(stripJsonFence(raw.trim()));
            List<GraphExtractionResult.EntityMention> entities = parseEntities(object.getJSONArray("entities"));
            List<GraphExtractionResult.RelationMention> relations = parseRelations(object.getJSONArray("relations"));
            return new GraphExtractionResult(entities, relations);
        } catch (JSONException | IllegalArgumentException e) {
            log.debug("LLM graph extraction output is not valid JSON: {}", raw, e);
            return empty();
        }
    }

    private String buildUserPrompt(String content, List<GraphExtractionResult.EntityMention> seedEntities) {
        String safeContent = content.length() <= Math.max(200, properties.getMaxChunkChars())
                ? content
                : content.substring(0, Math.max(200, properties.getMaxChunkChars()));
        StringBuilder prompt = new StringBuilder();
        prompt.append("Return at most ")
                .append(Math.max(1, properties.getMaxLlmEntities()))
                .append(" entities and at most ")
                .append(Math.max(1, properties.getMaxLlmRelations()))
                .append(" relations.\n\n");
        prompt.append("Seed entities from deterministic extractors:\n");
        if (seedEntities == null || seedEntities.isEmpty()) {
            prompt.append("[]\n\n");
        } else {
            prompt.append(JSON.toJSONString(seedEntities.stream()
                    .limit(Math.max(1, properties.getMaxLlmEntities()))
                    .map(entity -> new SeedEntity(entity.name(), entity.entityType(), entity.aliases()))
                    .toList()));
            prompt.append("\n\n");
        }
        prompt.append("Input chunk:\n").append(safeContent);
        return prompt.toString();
    }

    private static List<GraphExtractionResult.EntityMention> parseEntities(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<GraphExtractionResult.EntityMention> entities = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.getString("name");
            if (!StringUtils.hasText(name)) {
                continue;
            }
            List<String> aliases = parseAliases(item.getJSONArray("aliases"));
            entities.add(new GraphExtractionResult.EntityMention(
                    name.trim(),
                    normalize(name),
                    normalizeType(item.getString("type"), "CONCEPT"),
                    aliases,
                    confidence(item.getDouble("confidence"), 0.66d)
            ));
        }
        return entities;
    }

    private static List<GraphExtractionResult.RelationMention> parseRelations(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<GraphExtractionResult.RelationMention> relations = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JSONObject item = array.getJSONObject(i);
            if (item == null) {
                continue;
            }
            String source = item.getString("source");
            String target = item.getString("target");
            if (!StringUtils.hasText(source) || !StringUtils.hasText(target)) {
                continue;
            }
            double confidence = confidence(item.getDouble("confidence"), 0.66d);
            GraphExtractionResult.EntityMention sourceMention = mention(
                    source,
                    normalizeType(item.getString("sourceType"), "CONCEPT"),
                    confidence
            );
            GraphExtractionResult.EntityMention targetMention = mention(
                    target,
                    normalizeType(item.getString("targetType"), "CONCEPT"),
                    confidence
            );
            relations.add(new GraphExtractionResult.RelationMention(
                    sourceMention,
                    normalizeRelation(item.getString("relationType")),
                    targetMention,
                    item.getString("evidence"),
                    confidence
            ));
        }
        return relations;
    }

    private static List<String> parseAliases(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<String> aliases = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String alias = array.getString(i);
            if (StringUtils.hasText(alias)) {
                aliases.add(alias.trim());
            }
        }
        return aliases;
    }

    private static GraphExtractionResult.EntityMention mention(String name, String type, double confidence) {
        String cleaned = name == null ? "" : name.trim();
        return new GraphExtractionResult.EntityMention(cleaned, normalize(cleaned), type, List.of(), confidence);
    }

    private static double confidence(Double value, double fallback) {
        if (value == null || value.isNaN()) {
            return fallback;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String normalizeType(String value, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeRelation(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、！？“”‘’（）()【】《》\\[\\]{}]+", "")
                .trim();
    }

    private static String stripJsonFence(String value) {
        String normalized = value;
        if (normalized.startsWith("```json")) {
            normalized = normalized.substring(7);
        } else if (normalized.startsWith("```")) {
            normalized = normalized.substring(3);
        }
        if (normalized.endsWith("```")) {
            normalized = normalized.substring(0, normalized.length() - 3);
        }
        return normalized.trim();
    }

    private static GraphExtractionResult empty() {
        return new GraphExtractionResult(List.of(), List.of());
    }

    private record SeedEntity(String name, String type, List<String> aliases) {
    }
}
