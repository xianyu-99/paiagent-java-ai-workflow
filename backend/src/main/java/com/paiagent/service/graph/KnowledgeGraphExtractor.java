package com.paiagent.service.graph;

import com.paiagent.entity.KnowledgeChunk;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class KnowledgeGraphExtractor {

    private static final Pattern SLA_PATTERN = Pattern.compile(
            "(\\d+\\s*(?:分钟|小时|个?工作日|天|h|H)|SLA\\s*[^，。；\\n]{0,24})"
    );

    private static final Pattern MONEY_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?\\s*(?:元|万元|万))"
    );

    private static final List<EntityPattern> ENTITY_PATTERNS = List.of(
            entity("报销", "PROCESS", "报销流程", "费用报销", "差旅报销", "招待费报销", "培训费报销"),
            entity("请假", "PROCESS", "年假", "请假申请", "休假", "假期"),
            entity("VPN", "SYSTEM", "vpn", "VPN账号", "VPN证书", "公司VPN"),
            entity("工单", "PROCESS", "客户工单", "服务工单", "升级工单"),
            entity("电脑申请", "PROCESS", "新员工电脑", "设备申请", "电脑领用"),
            entity("财务", "DEPARTMENT", "财务部", "财务审核"),
            entity("HR", "DEPARTMENT", "人事", "人力资源", "HRBP"),
            entity("IT", "DEPARTMENT", "IT部门", "技术支持", "IT支持", "二线团队"),
            entity("行政", "DEPARTMENT", "行政部"),
            entity("直属主管", "ROLE", "主管", "上级主管", "部门主管"),
            entity("总监", "ROLE", "业务总监", "部门总监"),
            entity("人工客服", "ROLE", "人工处理", "人工服务", "人工升级"),
            entity("发票", "MATERIAL", "电子发票", "纸质发票", "原始发票"),
            entity("报销单", "MATERIAL", "费用报销单", "报销申请单"),
            entity("审批单", "MATERIAL", "差旅申请单", "审批截图"),
            entity("内部系统", "SYSTEM", "OA", "ERP", "邮箱", "企业微信", "知识库", "工单系统")
    );

    public GraphExtractionResult extract(KnowledgeChunk chunk) {
        if (chunk == null || !StringUtils.hasText(chunk.getContent())) {
            return new GraphExtractionResult(List.of(), List.of());
        }
        String content = chunk.getContent();
        Map<String, GraphExtractionResult.EntityMention> entitiesByName = new LinkedHashMap<>();
        for (GraphExtractionResult.EntityMention mention : matchEntities(content)) {
            entitiesByName.putIfAbsent(mention.normalizedName(), mention);
        }

        List<GraphExtractionResult.EntityMention> entities = new ArrayList<>(entitiesByName.values());
        List<GraphExtractionResult.RelationMention> relations = inferRelations(content, entities);
        return new GraphExtractionResult(entities, relations);
    }

    public List<GraphExtractionResult.EntityMention> matchEntities(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String normalizedText = normalize(text);
        List<GraphExtractionResult.EntityMention> matches = new ArrayList<>();
        for (EntityPattern pattern : ENTITY_PATTERNS) {
            boolean matched = normalize(pattern.name()).length() > 0
                    && normalizedText.contains(normalize(pattern.name()));
            if (!matched) {
                for (String alias : pattern.aliases()) {
                    if (normalizedText.contains(normalize(alias))) {
                        matched = true;
                        break;
                    }
                }
            }
            if (matched) {
                matches.add(new GraphExtractionResult.EntityMention(
                        pattern.name(),
                        normalize(pattern.name()),
                        pattern.entityType(),
                        pattern.aliases(),
                        0.90
                ));
            }
        }
        return matches;
    }

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}，。；：、“”‘’（）【】《》]+", "")
                .trim();
    }

    private List<GraphExtractionResult.RelationMention> inferRelations(
            String content,
            List<GraphExtractionResult.EntityMention> entities
    ) {
        List<GraphExtractionResult.RelationMention> relations = new ArrayList<>();
        Optional<GraphExtractionResult.EntityMention> process = firstOfType(entities, "PROCESS")
                .or(() -> firstOfType(entities, "SYSTEM"));

        if (process.isEmpty()) {
            return relations;
        }

        GraphExtractionResult.EntityMention source = process.get();
        String evidence = compactEvidence(content);

        if (containsAny(content, "审批", "批准", "签字", "审核")) {
            entities.stream()
                    .filter(entity -> "ROLE".equals(entity.entityType()) || "DEPARTMENT".equals(entity.entityType()))
                    .forEach(target -> relations.add(relation(source, "requires_approval_by", target, evidence, 0.82)));
        }

        if (containsAny(content, "处理", "负责", "联系", "支持", "升级", "转人工", "转交")) {
            entities.stream()
                    .filter(entity -> "DEPARTMENT".equals(entity.entityType()) || "ROLE".equals(entity.entityType()))
                    .forEach(target -> relations.add(relation(source, "handled_by", target, evidence, 0.80)));
        }

        if (containsAny(content, "材料", "附件", "上传", "提交", "需要提供", "需提供")) {
            entities.stream()
                    .filter(entity -> "MATERIAL".equals(entity.entityType()))
                    .forEach(target -> relations.add(relation(source, "requires_material", target, evidence, 0.78)));
        }

        if (containsAny(content, "访问", "依赖", "影响", "连接", "连上")) {
            entities.stream()
                    .filter(entity -> "SYSTEM".equals(entity.entityType()))
                    .filter(target -> !target.normalizedName().equals(source.normalizedName()))
                    .forEach(target -> relations.add(relation(source, "depends_on", target, evidence, 0.76)));
        }

        if (shouldExtractSlaTargets(content, source)) {
            extractSlaTargets(content).forEach(target ->
                    relations.add(relation(source, "has_sla", target, evidence, 0.74)));
        }

        extractMoneyTargets(content).forEach(target ->
                relations.add(relation(source, "has_threshold", target, evidence, 0.72)));

        return deduplicate(relations);
    }

    private Optional<GraphExtractionResult.EntityMention> firstOfType(
            List<GraphExtractionResult.EntityMention> entities,
            String entityType
    ) {
        return entities.stream()
                .filter(entity -> entityType.equals(entity.entityType()))
                .findFirst();
    }

    private List<GraphExtractionResult.EntityMention> extractSlaTargets(String content) {
        List<GraphExtractionResult.EntityMention> targets = new ArrayList<>();
        Matcher matcher = SLA_PATTERN.matcher(content);
        while (matcher.find() && targets.size() < 3) {
            String name = matcher.group(1).trim();
            targets.add(new GraphExtractionResult.EntityMention(
                    name,
                    normalize(name),
                    "SLA",
                    List.of(),
                    0.74
            ));
        }
        return targets;
    }

    private boolean shouldExtractSlaTargets(
            String content,
            GraphExtractionResult.EntityMention source
    ) {
        if (!StringUtils.hasText(content)) {
            return false;
        }
        if (containsAny(content, "SLA", "处理时限", "解决时限", "超时")) {
            return true;
        }
        return "PROCESS".equals(source.entityType())
                && containsAny(content, "工单", "响应", "工作日", "小时内", "分钟内", "完成", "审批", "审核", "处理");
    }

    private List<GraphExtractionResult.EntityMention> extractMoneyTargets(String content) {
        List<GraphExtractionResult.EntityMention> targets = new ArrayList<>();
        Matcher matcher = MONEY_PATTERN.matcher(content);
        while (matcher.find() && targets.size() < 3) {
            String name = matcher.group(1).trim();
            targets.add(new GraphExtractionResult.EntityMention(
                    name,
                    normalize(name),
                    "THRESHOLD",
                    List.of(),
                    0.72
            ));
        }
        return targets;
    }

    private GraphExtractionResult.RelationMention relation(
            GraphExtractionResult.EntityMention source,
            String relationType,
            GraphExtractionResult.EntityMention target,
            String evidence,
            double confidence
    ) {
        return new GraphExtractionResult.RelationMention(source, relationType, target, evidence, confidence);
    }

    private List<GraphExtractionResult.RelationMention> deduplicate(
            List<GraphExtractionResult.RelationMention> relations
    ) {
        Map<String, GraphExtractionResult.RelationMention> deduplicated = new LinkedHashMap<>();
        for (GraphExtractionResult.RelationMention relation : relations) {
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
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 240) + "...";
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

    private static EntityPattern entity(String name, String entityType, String... aliases) {
        return new EntityPattern(name, entityType, List.of(aliases));
    }

    private record EntityPattern(String name, String entityType, List<String> aliases) {
    }
}
