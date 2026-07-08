package com.paiagent.service.graph;

import com.paiagent.config.RagGraphExtractionProperties;
import com.paiagent.entity.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class KnowledgeGraphExtractor {

    private static final int MAX_ENTITIES = 32;
    private static final int MAX_RELATIONS = 48;
    private static final double DICTIONARY_CONFIDENCE = 0.86d;

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?；;\\n]+");
    private static final Pattern BOOK_TITLE_PATTERN = Pattern.compile("[《\\[]([^》\\]\\n]{2,40})[》\\]]");
    private static final Pattern UPPER_TOKEN_PATTERN = Pattern.compile("\\b[A-Z][A-Z0-9_-]{1,24}\\b");
    private static final Pattern SUFFIX_ENTITY_PATTERN = Pattern.compile(
            "([\\p{IsHan}A-Za-z0-9_-]{0,18}?(?:流程|申请|报销|开通|归还|审批|审核|复核|工单|故障|问题|系统|平台|应用|服务|账号|证书|邮箱|部门|团队|中心|服务台|主管|经理|负责人|审批人|审核人|管理员|专员|客服|员工|材料|附件|发票|凭证|申请单|报销单|截图|证明|说明|合同|单据|清单|SLA))"
    );
    private static final Pattern SLA_PATTERN = Pattern.compile(
            "(\\d+\\s*(?:分钟|小时|个?工作日|天|h|H)|SLA\\s*[^，。；;\\n]{0,24})"
    );
    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s*(?:万元|元|万|人民币|CNY|RMB))"
    );

    private static final Set<String> ALLOWED_ENTITY_TYPES = Set.of(
            "PROCESS", "ROLE", "PERSON", "DEPARTMENT", "MATERIAL", "SYSTEM",
            "SLA", "THRESHOLD", "CONCEPT", "DOCUMENT"
    );
    private static final Set<String> ALLOWED_RELATION_TYPES = Set.of(
            "requires_material", "requires_approval_by", "handled_by", "responsible_for",
            "depends_on", "has_sla", "has_threshold", "impacts", "causes", "related_to"
    );

    private final RagGraphExtractionProperties properties;
    private final List<GraphSemanticExtractor> semanticExtractors;

    public KnowledgeGraphExtractor() {
        this(new RagGraphExtractionProperties(), List.of());
    }

    @Autowired
    public KnowledgeGraphExtractor(RagGraphExtractionProperties properties,
                                   ObjectProvider<GraphSemanticExtractor> semanticExtractors) {
        this(properties, semanticExtractors == null ? List.of() : semanticExtractors.orderedStream().toList());
    }

    public KnowledgeGraphExtractor(RagGraphExtractionProperties properties,
                                   List<GraphSemanticExtractor> semanticExtractors) {
        this.properties = properties == null ? new RagGraphExtractionProperties() : properties;
        this.semanticExtractors = semanticExtractors == null ? List.of() : semanticExtractors;
    }

    public GraphExtractionResult extract(KnowledgeChunk chunk) {
        if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
            return new GraphExtractionResult(List.of(), List.of());
        }

        String content = chunk.getContent();
        Map<String, GraphExtractionResult.EntityMention> entities = new LinkedHashMap<>();
        for (GraphExtractionResult.EntityMention mention : matchEntities(content)) {
            putEntity(entities, mention);
        }

        List<GraphExtractionResult.RelationMention> relations = inferRelations(content, entities);
        mergeSemanticExtractions(content, entities, relations);
        for (GraphExtractionResult.RelationMention relation : relations) {
            putEntity(entities, relation.source());
            putEntity(entities, relation.target());
        }

        return new GraphExtractionResult(
                new ArrayList<>(entities.values()).stream().limit(MAX_ENTITIES).toList(),
                deduplicate(relations).stream().limit(MAX_RELATIONS).toList()
        );
    }

    public List<GraphExtractionResult.EntityMention> matchEntities(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        Map<String, GraphExtractionResult.EntityMention> entities = new LinkedHashMap<>();
        extractQuotedEntities(text, entities);
        extractUpperTokenEntities(text, entities);
        extractSuffixEntities(text, entities);
        extractDictionaryEntities(text, entities);
        for (String sentence : splitSentences(text)) {
            extractCueEntities(sentence, entities);
            if (entities.size() >= MAX_ENTITIES) {
                break;
            }
        }
        return new ArrayList<>(entities.values()).stream().limit(MAX_ENTITIES).toList();
    }

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、！？“”‘’（）()【】《》\\[\\]{}]+", "")
                .trim();
    }

    private void extractQuotedEntities(String text, Map<String, GraphExtractionResult.EntityMention> entities) {
        Matcher matcher = BOOK_TITLE_PATTERN.matcher(text);
        while (matcher.find() && entities.size() < MAX_ENTITIES) {
            String name = cleanEntityCandidate(matcher.group(1));
            addCandidate(entities, name, "DOCUMENT", 0.76);
        }
    }

    private void extractUpperTokenEntities(String text, Map<String, GraphExtractionResult.EntityMention> entities) {
        Matcher matcher = UPPER_TOKEN_PATTERN.matcher(text);
        while (matcher.find() && entities.size() < MAX_ENTITIES) {
            String name = cleanEntityCandidate(matcher.group());
            addCandidate(entities, name, inferEntityType(name, text), 0.78);
        }
    }

    private void extractSuffixEntities(String text, Map<String, GraphExtractionResult.EntityMention> entities) {
        Matcher matcher = SUFFIX_ENTITY_PATTERN.matcher(text);
        while (matcher.find() && entities.size() < MAX_ENTITIES) {
            String name = cleanEntityCandidate(matcher.group(1));
            addCandidate(entities, name, inferEntityType(name, text), 0.74);
        }
    }

    private void extractDictionaryEntities(String text, Map<String, GraphExtractionResult.EntityMention> entities) {
        if (!properties.isDictionaryEnabled() || !StringUtils.hasText(text)) {
            return;
        }
        String normalizedText = normalize(text);
        for (DictionaryEntry entry : dictionaryEntries()) {
            if (entities.size() >= MAX_ENTITIES) {
                return;
            }
            if (dictionaryEntryMatches(normalizedText, entry)) {
                putEntity(entities, new GraphExtractionResult.EntityMention(
                        entry.name(),
                        normalize(entry.name()),
                        safeEntityType(entry.entityType(), inferEntityType(entry.name(), text)),
                        entry.aliases(),
                        DICTIONARY_CONFIDENCE
                ));
            }
        }
    }

    private boolean dictionaryEntryMatches(String normalizedText, DictionaryEntry entry) {
        if (normalizedText.contains(normalize(entry.name()))) {
            return true;
        }
        for (String alias : entry.aliases()) {
            if (StringUtils.hasText(alias) && normalizedText.contains(normalize(alias))) {
                return true;
            }
        }
        return false;
    }

    private List<DictionaryEntry> dictionaryEntries() {
        List<String> configured = properties.getDictionaryEntries();
        if (configured == null || configured.isEmpty()) {
            return List.of();
        }
        List<DictionaryEntry> entries = new ArrayList<>();
        for (String value : configured) {
            for (String token : value.split("[;；\\n]")) {
                DictionaryEntry entry = parseDictionaryEntry(token);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private DictionaryEntry parseDictionaryEntry(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String[] parts = value.split("\\|", -1);
        String name = cleanEntityCandidate(parts[0]);
        if (!isUsefulEntity(name)) {
            return null;
        }
        String entityType = parts.length > 1 && StringUtils.hasText(parts[1])
                ? parts[1].trim().toUpperCase(Locale.ROOT)
                : inferEntityType(name, name);
        List<String> aliases = parts.length > 2
                ? Arrays.stream(parts[2].split("[,，]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList()
                : List.of();
        return new DictionaryEntry(name, safeEntityType(entityType, inferEntityType(name, name)), aliases);
    }

    private void extractCueEntities(String sentence, Map<String, GraphExtractionResult.EntityMention> entities) {
        addCandidate(entities, subjectBefore(sentence, "需要|需|应|必须|可以|通过|访问|连接|登录|使用|超过|在"), null, 0.70);
        extractTargetsAfter(sentence, "(?:提交|上传|提供|准备|补充|附上)", "MATERIAL", entities, 0.73);
        extractTargetsAfter(sentence, "(?:通过|访问|连接|登录|使用|依赖)", "SYSTEM", entities, 0.72);
        extractTargetsBefore(sentence, "(?:审批|审核|批准|复核|签字)", "ROLE", entities, 0.72);
        extractTargetsBefore(sentence, "(?:处理|负责|支持|跟进)", "DEPARTMENT", entities, 0.70);
    }

    private List<GraphExtractionResult.RelationMention> inferRelations(
            String content,
            Map<String, GraphExtractionResult.EntityMention> knownEntities
    ) {
        List<GraphExtractionResult.RelationMention> relations = new ArrayList<>();
        GraphExtractionResult.EntityMention fallbackSource = firstSourceCandidate(knownEntities);

        for (String sentence : splitSentences(content)) {
            GraphExtractionResult.EntityMention source = sourceForSentence(sentence, knownEntities, fallbackSource);
            if (source == null) {
                continue;
            }
            String evidence = compactEvidence(sentence);

            extractRelationTargets(sentence, "(?:提交|上传|提供|准备|补充|附上)", "requires_material",
                    "MATERIAL", source, evidence, relations, 0.78);
            extractApprovalRelations(sentence, source, evidence, relations);
            extractRelationTargets(sentence, "(?:联系|转交|升级给|提交给|由)", "handled_by",
                    "DEPARTMENT", source, evidence, relations, 0.75,
                    "(?:处理|负责|支持|跟进|解决)");
            extractRelationTargets(sentence, "(?:通过|访问|连接|登录|使用|依赖)", "depends_on",
                    "SYSTEM", source, evidence, relations, 0.74);

            if (shouldExtractSlaTargets(sentence, source)) {
                for (GraphExtractionResult.EntityMention target : extractSlaTargets(sentence)) {
                    relations.add(relation(source, "has_sla", target, evidence, 0.74));
                }
            }
            for (GraphExtractionResult.EntityMention target : extractMoneyTargets(sentence)) {
                relations.add(relation(source, "has_threshold", target, evidence, 0.72));
            }
        }
        return deduplicate(relations);
    }

    private void mergeSemanticExtractions(String content,
                                          Map<String, GraphExtractionResult.EntityMention> entities,
                                          List<GraphExtractionResult.RelationMention> relations) {
        if (semanticExtractors.isEmpty() || !StringUtils.hasText(content)) {
            return;
        }
        List<GraphExtractionResult.EntityMention> seedEntities = new ArrayList<>(entities.values());
        for (GraphSemanticExtractor semanticExtractor : semanticExtractors) {
            try {
                GraphExtractionResult semanticResult = semanticExtractor.extract(content, seedEntities);
                mergeSemanticResult(content, semanticResult, entities, relations);
            } catch (Exception e) {
                log.warn("Semantic graph extraction skipped: {}", e.getMessage());
            }
        }
    }

    private void mergeSemanticResult(String content,
                                     GraphExtractionResult semanticResult,
                                     Map<String, GraphExtractionResult.EntityMention> entities,
                                     List<GraphExtractionResult.RelationMention> relations) {
        if (semanticResult == null) {
            return;
        }
        for (GraphExtractionResult.EntityMention mention : semanticResult.entities()) {
            GraphExtractionResult.EntityMention validated = validateSemanticEntity(mention);
            if (validated != null) {
                putEntity(entities, validated);
            }
        }
        for (GraphExtractionResult.RelationMention mention : semanticResult.relations()) {
            GraphExtractionResult.RelationMention validated = validateSemanticRelation(content, mention, entities);
            if (validated != null) {
                relations.add(validated);
                putEntity(entities, validated.source());
                putEntity(entities, validated.target());
            }
        }
    }

    private GraphExtractionResult.EntityMention validateSemanticEntity(GraphExtractionResult.EntityMention mention) {
        if (mention == null
                || !isUsefulEntity(mention.name())
                || mention.confidence() < properties.getMinLlmConfidence()) {
            return null;
        }
        String entityType = safeEntityType(mention.entityType(), inferEntityType(mention.name(), mention.name()));
        return new GraphExtractionResult.EntityMention(
                cleanEntityCandidate(mention.name()),
                normalize(mention.name()),
                entityType,
                cleanAliases(mention.aliases()),
                boundedConfidence(mention.confidence())
        );
    }

    private GraphExtractionResult.RelationMention validateSemanticRelation(
            String content,
            GraphExtractionResult.RelationMention mention,
            Map<String, GraphExtractionResult.EntityMention> entities
    ) {
        String relationType = normalizeRelationType(mention == null ? null : mention.relationType());
        if (mention == null
                || mention.source() == null
                || mention.target() == null
                || !ALLOWED_RELATION_TYPES.contains(relationType)
                || mention.confidence() < properties.getMinLlmConfidence()) {
            return null;
        }
        String evidence = mention.evidence() == null ? "" : mention.evidence().trim();
        if (!StringUtils.hasText(evidence) || !content.contains(evidence)) {
            return null;
        }
        String sourceName = cleanEntityCandidate(mention.source().name());
        String targetName = cleanEntityCandidate(mention.target().name());
        if (!isUsefulEntity(sourceName) || !isUsefulEntity(targetName) || normalize(sourceName).equals(normalize(targetName))) {
            return null;
        }
        String normalizedEvidence = normalize(evidence);
        if (!normalizedEvidence.contains(normalize(sourceName)) || !normalizedEvidence.contains(normalize(targetName))) {
            return null;
        }

        GraphExtractionResult.EntityMention source = normalizedSemanticEndpoint(
                mention.source(), sourceName, content, entities);
        GraphExtractionResult.EntityMention target = normalizedSemanticEndpoint(
                mention.target(), targetName, content, entities);
        if (source == null || target == null || source.normalizedName().equals(target.normalizedName())) {
            return null;
        }
        return relation(source, relationType, target, compactEvidence(evidence), boundedConfidence(mention.confidence()));
    }

    private GraphExtractionResult.EntityMention normalizedSemanticEndpoint(
            GraphExtractionResult.EntityMention mention,
            String name,
            String content,
            Map<String, GraphExtractionResult.EntityMention> entities
    ) {
        String normalizedName = normalize(name);
        GraphExtractionResult.EntityMention existing = entities.get(normalizedName);
        if (existing != null) {
            return existing;
        }
        String entityType = safeEntityType(mention.entityType(), inferEntityType(name, content));
        return new GraphExtractionResult.EntityMention(
                name,
                normalizedName,
                entityType,
                cleanAliases(mention.aliases()),
                boundedConfidence(mention.confidence())
        );
    }

    private void extractRelationTargets(String sentence,
                                        String triggerRegex,
                                        String relationType,
                                        String targetType,
                                        GraphExtractionResult.EntityMention source,
                                        String evidence,
                                        List<GraphExtractionResult.RelationMention> relations,
                                        double confidence) {
        extractRelationTargets(sentence, triggerRegex, relationType, targetType, source, evidence, relations, confidence, null);
    }

    private void extractRelationTargets(String sentence,
                                        String triggerRegex,
                                        String relationType,
                                        String targetType,
                                        GraphExtractionResult.EntityMention source,
                                        String evidence,
                                        List<GraphExtractionResult.RelationMention> relations,
                                        double confidence,
                                        String terminalRegex) {
        Pattern pattern = Pattern.compile(triggerRegex + "([^，。；;]{2,80})");
        Matcher matcher = pattern.matcher(sentence);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (StringUtils.hasText(terminalRegex)) {
                Matcher terminal = Pattern.compile("(.{1,50}?)" + terminalRegex).matcher(raw);
                if (terminal.find()) {
                    raw = terminal.group(1);
                }
            }
            for (String candidate : splitEntityList(raw)) {
                String name = cleanEntityCandidate(candidate);
                if (!isUsefulEntity(name) || sameEntity(source, name)) {
                    continue;
                }
                GraphExtractionResult.EntityMention target = mention(
                        name,
                        targetType == null ? inferEntityType(name, sentence) : targetType,
                        confidence
                );
                relations.add(relation(source, relationType, target, evidence, confidence));
            }
        }
    }

    private void extractApprovalRelations(String sentence,
                                          GraphExtractionResult.EntityMention source,
                                          String evidence,
                                          List<GraphExtractionResult.RelationMention> relations) {
        Pattern pattern = Pattern.compile("(?:由|经|经过|需要|需)?([^，。；;]{2,40}?)(?:审批|审核|批准|复核|签字)");
        Matcher matcher = pattern.matcher(sentence);
        while (matcher.find()) {
            for (String candidate : splitEntityList(matcher.group(1))) {
                String name = cleanEntityCandidate(candidate);
                if (!isUsefulEntity(name) || sameEntity(source, name)) {
                    continue;
                }
                GraphExtractionResult.EntityMention target = mention(
                        name,
                        inferReviewerType(name),
                        0.80
                );
                relations.add(relation(source, "requires_approval_by", target, evidence, 0.80));
            }
        }
    }

    private GraphExtractionResult.EntityMention sourceForSentence(
            String sentence,
            Map<String, GraphExtractionResult.EntityMention> knownEntities,
            GraphExtractionResult.EntityMention fallbackSource
    ) {
        String subject = subjectBefore(sentence, "需要|需|应|必须|可以|通过|访问|连接|登录|使用|超过|在|由|经|经过");
        if (isUsefulEntity(subject)) {
            return mention(subject, inferEntityType(subject, sentence), 0.76);
        }
        for (GraphExtractionResult.EntityMention entity : knownEntities.values()) {
            if (sentenceContainsEntity(sentence, entity)) {
                return entity;
            }
        }
        return fallbackSource;
    }

    private GraphExtractionResult.EntityMention firstSourceCandidate(
            Map<String, GraphExtractionResult.EntityMention> entities
    ) {
        for (GraphExtractionResult.EntityMention entity : entities.values()) {
            if ("PROCESS".equals(entity.entityType()) || "SYSTEM".equals(entity.entityType())) {
                return entity;
            }
        }
        return entities.values().stream().findFirst().orElse(null);
    }

    private String subjectBefore(String sentence, String triggerRegex) {
        Matcher matcher = Pattern.compile("^(.{2,50}?)(" + triggerRegex + ")").matcher(sentence.trim());
        if (!matcher.find()) {
            return null;
        }
        return cleanEntityCandidate(lastPhrase(matcher.group(1)));
    }

    private void extractTargetsAfter(String sentence,
                                     String triggerRegex,
                                     String targetType,
                                     Map<String, GraphExtractionResult.EntityMention> entities,
                                     double confidence) {
        Matcher matcher = Pattern.compile(triggerRegex + "([^，。；;]{2,80})").matcher(sentence);
        while (matcher.find()) {
            for (String target : splitEntityList(matcher.group(1))) {
                addCandidate(entities, target, targetType, confidence);
            }
        }
    }

    private void extractTargetsBefore(String sentence,
                                      String terminalRegex,
                                      String targetType,
                                      Map<String, GraphExtractionResult.EntityMention> entities,
                                      double confidence) {
        Matcher matcher = Pattern.compile("([^，。；;]{2,40}?)" + terminalRegex).matcher(sentence);
        while (matcher.find()) {
            for (String target : splitEntityList(matcher.group(1))) {
                addCandidate(entities, target, targetType, confidence);
            }
        }
    }

    private List<String> splitSentences(String content) {
        if (!StringUtils.hasText(content)) {
            return List.of();
        }
        List<String> sentences = new ArrayList<>();
        for (String sentence : SENTENCE_SPLIT.split(content)) {
            String trimmed = sentence.trim();
            if (trimmed.length() >= 2) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    private List<String> splitEntityList(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        String normalized = value.replaceAll("(?:以及|或者|或|并且|同时|和|与|及|、|,|，|/|访问|连接|登录|使用|依赖|通过)", "|");
        List<String> result = new ArrayList<>();
        for (String part : normalized.split("\\|")) {
            String candidate = cleanEntityCandidate(part);
            if (isUsefulEntity(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private String cleanEntityCandidate(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.replaceAll("\\s+", "")
                .replaceAll("^[的在由将把和与及、,，。；;：:]+", "")
                .replaceAll("[,，。；;：:！？!?.、()（）\\[\\]【】《》]+$", "");
        value = value.replaceAll("^.*(?:并由|由|经|经过)", "");
        value = cutBeforeFirst(value, "需要", "需", "应", "必须", "可以", "可", "通过", "访问", "连接", "登录",
                "使用", "提交", "上传", "提供", "准备", "补充", "附上", "联系", "转交", "升级给", "交给", "超过", "在");
        value = value.replaceAll("^(需要|需|应|必须|可以|可|通过|访问|连接|登录|使用|提交|上传|提供|准备|补充|附上|联系|转交|升级给|交给|不了|无法|不能|不)", "");
        value = value.replaceAll("(过期后|到期后|时|后|前|中|内|完成|处理|审批|审核|批准|复核|签字|负责|支持|跟进|解决|即可|才可|才能)$", "");
        return value.trim();
    }

    private String cutBeforeFirst(String value, String... markers) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        int index = -1;
        for (String marker : markers) {
            int markerIndex = value.indexOf(marker);
            if (markerIndex > 0 && (index < 0 || markerIndex < index)) {
                index = markerIndex;
            }
        }
        return index > 0 ? value.substring(0, index) : value;
    }

    private String lastPhrase(String value) {
        if (value == null) {
            return null;
        }
        String[] parts = value.split("(?:，|,|。|；|;|如果|当|若|在|对|关于)");
        return parts.length == 0 ? value : parts[parts.length - 1];
    }

    private void addCandidate(Map<String, GraphExtractionResult.EntityMention> entities,
                              String rawName,
                              String preferredType,
                              double confidence) {
        String name = cleanEntityCandidate(rawName);
        if (!isUsefulEntity(name)) {
            return;
        }
        String type = preferredType == null ? inferEntityType(name, rawName) : preferredType;
        putEntity(entities, mention(name, type, confidence));
    }

    private void putEntity(Map<String, GraphExtractionResult.EntityMention> entities,
                           GraphExtractionResult.EntityMention mention) {
        if (mention == null || !StringUtils.hasText(mention.normalizedName())) {
            return;
        }
        GraphExtractionResult.EntityMention existing = entities.get(mention.normalizedName());
        if (existing == null || mention.confidence() > existing.confidence()) {
            entities.put(mention.normalizedName(), mention);
        }
    }

    private GraphExtractionResult.EntityMention mention(String name, String entityType, double confidence) {
        String cleaned = cleanEntityCandidate(name);
        return new GraphExtractionResult.EntityMention(
                cleaned,
                normalize(cleaned),
                safeEntityType(entityType, "CONCEPT"),
                List.of(),
                boundedConfidence(confidence)
        );
    }

    private String safeEntityType(String entityType, String fallback) {
        String safeFallback = StringUtils.hasText(fallback) ? fallback.toUpperCase(Locale.ROOT) : "CONCEPT";
        if (!ALLOWED_ENTITY_TYPES.contains(safeFallback)) {
            safeFallback = "CONCEPT";
        }
        if (!StringUtils.hasText(entityType)) {
            return safeFallback;
        }
        String normalizedType = entityType.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_ENTITY_TYPES.contains(normalizedType) ? normalizedType : safeFallback;
    }

    private List<String> cleanAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String alias : aliases) {
            String value = cleanEntityCandidate(alias);
            if (isUsefulEntity(value)) {
                cleaned.add(value);
            }
        }
        return new ArrayList<>(cleaned);
    }

    private double boundedConfidence(double confidence) {
        if (Double.isNaN(confidence)) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, confidence));
    }

    private String normalizeRelationType(String relationType) {
        return StringUtils.hasText(relationType) ? relationType.trim().toLowerCase(Locale.ROOT) : "";
    }

    private boolean isUsefulEntity(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        String normalized = normalize(value);
        if (normalized.length() < 2 || normalized.length() > 40) {
            return false;
        }
        return !normalized.matches("\\d+")
                && !List.of("需要", "提交", "上传", "提供", "处理", "审批", "审核", "完成", "如果", "可以").contains(normalized);
    }

    private String inferEntityType(String name, String context) {
        String value = normalize(name);
        String raw = name == null ? "" : name;
        if (raw.matches(".*\\d+\\s*(?:分钟|小时|个?工作日|天|h|H).*") || value.contains("sla") || value.contains("时限")) {
            return "SLA";
        }
        if (raw.matches(".*\\d+(?:\\.\\d+)?\\s*(?:万元|元|万|人民币|CNY|RMB).*")) {
            return "THRESHOLD";
        }
        if (containsAny(value, "系统", "平台", "应用", "服务", "账号", "证书", "邮箱", "vpn", "oa", "erp", "crm")) {
            return "SYSTEM";
        }
        if (containsAny(value, "部门", "团队", "中心", "服务台", "财务", "人事", "行政", "it", "hr", "支持")) {
            return "DEPARTMENT";
        }
        if (containsAny(value, "主管", "经理", "负责人", "审批人", "审核人", "管理员", "专员", "客服", "员工")) {
            return "ROLE";
        }
        if (containsAny(value, "材料", "附件", "发票", "凭证", "申请单", "报销单", "截图", "证明", "说明", "合同", "单据", "清单")) {
            return "MATERIAL";
        }
        if (containsAny(value, "流程", "申请", "报销", "开通", "归还", "审批", "审核", "工单", "请假", "入职", "离职", "故障", "变更", "采购", "预订")) {
            return "PROCESS";
        }
        return "CONCEPT";
    }

    private String inferReviewerType(String name) {
        String inferred = inferEntityType(name, name);
        if ("CONCEPT".equals(inferred)) {
            return "ROLE";
        }
        return inferred;
    }

    private List<GraphExtractionResult.EntityMention> extractSlaTargets(String content) {
        List<GraphExtractionResult.EntityMention> targets = new ArrayList<>();
        Matcher matcher = SLA_PATTERN.matcher(content);
        while (matcher.find() && targets.size() < 3) {
            String name = matcher.group(1).trim();
            targets.add(mention(name, "SLA", 0.74));
        }
        return targets;
    }

    private boolean shouldExtractSlaTargets(String content, GraphExtractionResult.EntityMention source) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        if (containsAny(content, "SLA", "处理时限", "解决时限", "响应时限", "超时")) {
            return true;
        }
        if (containsAny(content, "工作日内", "小时内", "分钟内")
                && containsAny(content, "完成", "处理", "审批", "审核", "解决", "响应")) {
            return true;
        }
        return source != null
                && ("PROCESS".equals(source.entityType()) || "SYSTEM".equals(source.entityType()))
                && containsAny(content, "工单", "响应", "工作日", "小时内", "分钟内", "完成", "审批", "审核", "处理");
    }

    private List<GraphExtractionResult.EntityMention> extractMoneyTargets(String content) {
        List<GraphExtractionResult.EntityMention> targets = new ArrayList<>();
        Matcher matcher = MONEY_PATTERN.matcher(content);
        while (matcher.find() && targets.size() < 3) {
            String name = matcher.group(1).trim();
            targets.add(mention(name, "THRESHOLD", 0.72));
        }
        return targets;
    }

    private GraphExtractionResult.RelationMention relation(GraphExtractionResult.EntityMention source,
                                                           String relationType,
                                                           GraphExtractionResult.EntityMention target,
                                                           String evidence,
                                                           double confidence) {
        return new GraphExtractionResult.RelationMention(source, relationType, target, evidence, confidence);
    }

    private List<GraphExtractionResult.RelationMention> deduplicate(
            List<GraphExtractionResult.RelationMention> relations
    ) {
        Map<String, GraphExtractionResult.RelationMention> deduplicated = new LinkedHashMap<>();
        for (GraphExtractionResult.RelationMention relation : relations) {
            if (relation == null || relation.source() == null || relation.target() == null) {
                continue;
            }
            String key = relation.source().normalizedName()
                    + "|"
                    + relation.relationType()
                    + "|"
                    + relation.target().normalizedName();
            deduplicated.putIfAbsent(key, relation);
        }
        return new ArrayList<>(deduplicated.values());
    }

    private String compactEvidence(String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
    }

    private boolean sentenceContainsEntity(String sentence, GraphExtractionResult.EntityMention entity) {
        if (entity == null || !StringUtils.hasText(sentence)) {
            return false;
        }
        String normalizedSentence = normalize(sentence);
        return normalizedSentence.contains(entity.normalizedName());
    }

    private boolean sameEntity(GraphExtractionResult.EntityMention source, String name) {
        return source != null && source.normalizedName().equals(normalize(name));
    }

    private boolean containsAny(String content, String... keywords) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private record DictionaryEntry(String name, String entityType, List<String> aliases) {
    }
}
