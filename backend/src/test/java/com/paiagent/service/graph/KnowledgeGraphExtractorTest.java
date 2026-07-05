package com.paiagent.service.graph;

import com.paiagent.entity.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphExtractorTest {

    private final KnowledgeGraphExtractor extractor = new KnowledgeGraphExtractor();

    @Test
    void shouldExtractServiceDeskRelationsFromPolicyChunk() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent("""
                差旅报销需要提交报销单和发票。
                报销金额超过 5000 元时，需要直属主管审批，并由财务审核。
                财务审核通常在 3 个工作日内完成。
                """);

        GraphExtractionResult result = extractor.extract(chunk);

        assertThat(result.entities())
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("报销", "报销单", "发票", "直属主管", "财务");
        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .contains("requires_material", "requires_approval_by", "has_threshold", "has_sla");
    }

    @Test
    void shouldNotTreatOrdinaryBookingDurationAsSla() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent("\u4f1a\u8bae\u5ba4\u901a\u8fc7OA\u7cfb\u7edf\u9884\u8ba2\uff0c"
                + "\u4f7f\u7528\u5b8c\u6bd5\u540e\u9700\u5c06\u5ea7\u6905\u5f52\u4f4d\uff0c"
                + "\u8fde\u7eed\u9884\u8ba2\u8d85\u8fc74\u5c0f\u65f6\u9700\u8bf4\u660e\u7279\u6b8a\u4e8b\u7531\u3002");

        GraphExtractionResult result = extractor.extract(chunk);

        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .doesNotContain("has_sla");
    }

    @Test
    void shouldNormalizeQueryEntityAliases() {
        assertThat(extractor.matchEntities("VPN证书过期后访问不了内部系统"))
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("VPN", "内部系统");
    }
}
