package com.paiagent.service.graph;

import com.paiagent.config.RagGraphExtractionProperties;
import com.paiagent.entity.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeGraphExtractorTest {

    private final KnowledgeGraphExtractor extractor = new KnowledgeGraphExtractor();

    @Test
    void shouldExtractDynamicEntitiesAndRelationsWithoutPredefinedDictionary() {
        KnowledgeChunk chunk = chunk("""
                \u8d44\u4ea7\u5f52\u8fd8\u7533\u8bf7\u9700\u8981\u63d0\u4ea4\u8bbe\u5907\u6e05\u5355\u548c\u5f52\u8fd8\u51ed\u8bc1\u3002
                \u79bb\u804c\u5458\u5de5\u5fc5\u987b\u7ecf\u76f4\u5c5e\u4e3b\u7ba1\u5ba1\u6279\uff0c\u5e76\u7531\u884c\u653f\u56e2\u961f\u5904\u7406\u3002
                \u884c\u653f\u56e2\u961f\u9700\u57282\u4e2a\u5de5\u4f5c\u65e5\u5185\u5b8c\u6210\u3002
                """);

        GraphExtractionResult result = extractor.extract(chunk);

        assertThat(result.entities())
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("\u8d44\u4ea7\u5f52\u8fd8\u7533\u8bf7", "\u8bbe\u5907\u6e05\u5355", "\u5f52\u8fd8\u51ed\u8bc1", "\u76f4\u5c5e\u4e3b\u7ba1", "\u884c\u653f\u56e2\u961f");
        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .contains("requires_material", "requires_approval_by", "handled_by", "has_sla");
    }

    @Test
    void shouldExtractPermissionOpeningAndSystemDependencyDynamically() {
        KnowledgeChunk chunk = chunk("""
                \u6743\u9650\u5f00\u901a\u7533\u8bf7\u9700\u8981\u63d0\u4f9b\u6743\u9650\u8bf4\u660e\u548c\u4e1a\u52a1\u8d1f\u8d23\u4eba\u5ba1\u6279\u8bb0\u5f55\u3002
                \u7533\u8bf7\u4eba\u901a\u8fc7IAM\u5e73\u53f0\u8bbf\u95ee\u8ba2\u5355\u7cfb\u7edf\uff0c\u8d85\u8fc720000\u5143\u7684\u6743\u9650\u9700\u7531\u98ce\u63a7\u7ecf\u7406\u590d\u6838\u3002
                """);

        GraphExtractionResult result = extractor.extract(chunk);

        assertThat(result.entities())
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("\u6743\u9650\u5f00\u901a\u7533\u8bf7", "IAM\u5e73\u53f0", "\u8ba2\u5355\u7cfb\u7edf", "20000\u5143");
        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .contains("requires_material", "requires_approval_by", "depends_on", "has_threshold");
    }

    @Test
    void shouldExtractThresholdWrittenAsWan() {
        KnowledgeChunk chunk = chunk(
                "\u91c7\u8d2d\u7533\u8bf7\u8d85\u8fc73\u4e07\u9700\u8981\u90e8\u95e8\u7ecf\u7406\u5ba1\u6279\u3002");

        GraphExtractionResult result = extractor.extract(chunk);

        assertThat(result.entities())
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("3\u4e07");
        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .contains("has_threshold");
    }

    @Test
    void shouldNotTreatOrdinaryBookingDurationAsSla() {
        KnowledgeChunk chunk = chunk("\u4f1a\u8bae\u5ba4\u901a\u8fc7OA\u7cfb\u7edf\u9884\u8ba2\uff0c"
                + "\u4f7f\u7528\u5b8c\u6bd5\u540e\u9700\u5c06\u5ea7\u6905\u5f52\u4f4d\uff0c"
                + "\u8fde\u7eed\u9884\u8ba2\u8d85\u8fc74\u5c0f\u65f6\u9700\u8bf4\u660e\u7279\u6b8a\u4e8b\u7531\u3002");

        GraphExtractionResult result = extractor.extract(chunk);

        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .doesNotContain("has_sla");
    }

    @Test
    void shouldNormalizeQueryEntitiesDynamically() {
        assertThat(extractor.matchEntities("VPN\u8bc1\u4e66\u8fc7\u671f\u540e\u8bbf\u95ee\u4e0d\u4e86\u5185\u90e8\u7cfb\u7edf"))
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("VPN", "VPN\u8bc1\u4e66", "\u5185\u90e8\u7cfb\u7edf");
    }

    @Test
    void shouldExtractConfiguredDictionaryEntityByAlias() {
        RagGraphExtractionProperties properties = new RagGraphExtractionProperties();
        properties.setDictionaryEntries(List.of(
                "\u652f\u4ed8\u7cfb\u7edf|SYSTEM|Payment Service,payment-service;\u8ba2\u5355\u7cfb\u7edf|SYSTEM|order-service"
        ));
        KnowledgeGraphExtractor hybridExtractor = new KnowledgeGraphExtractor(properties, List.of());

        assertThat(hybridExtractor.matchEntities("payment-service\u4f9d\u8d56\u8ba2\u5355\u7cfb\u7edf"))
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("\u652f\u4ed8\u7cfb\u7edf", "\u8ba2\u5355\u7cfb\u7edf");
    }

    @Test
    void shouldMergeValidatedSemanticRelationsFromLlmLayer() {
        GraphExtractionResult semanticResult = new GraphExtractionResult(
                List.of(
                        mention("\u5f20\u4e09", "PERSON", 0.91),
                        mention("\u652f\u4ed8\u7cfb\u7edf", "SYSTEM", 0.90),
                        mention("\u8ba2\u5355\u7cfb\u7edf", "SYSTEM", 0.90)
                ),
                List.of(
                        relation("\u5f20\u4e09", "PERSON", "responsible_for",
                                "\u652f\u4ed8\u7cfb\u7edf", "SYSTEM", "\u5f20\u4e09\u8d1f\u8d23\u652f\u4ed8\u7cfb\u7edf", 0.88),
                        relation("\u652f\u4ed8\u7cfb\u7edf", "SYSTEM", "depends_on",
                                "\u8ba2\u5355\u7cfb\u7edf", "SYSTEM", "\u652f\u4ed8\u7cfb\u7edf\u4f9d\u8d56\u8ba2\u5355\u7cfb\u7edf", 0.86)
                )
        );
        KnowledgeGraphExtractor hybridExtractor = new KnowledgeGraphExtractor(
                new RagGraphExtractionProperties(),
                List.of((content, seedEntities) -> semanticResult)
        );

        GraphExtractionResult result = hybridExtractor.extract(chunk(
                "\u5f20\u4e09\u8d1f\u8d23\u652f\u4ed8\u7cfb\u7edf\u3002\u652f\u4ed8\u7cfb\u7edf\u4f9d\u8d56\u8ba2\u5355\u7cfb\u7edf\u3002"));

        assertThat(result.entities())
                .extracting(GraphExtractionResult.EntityMention::name)
                .contains("\u5f20\u4e09", "\u652f\u4ed8\u7cfb\u7edf", "\u8ba2\u5355\u7cfb\u7edf");
        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .contains("responsible_for", "depends_on");
    }

    @Test
    void shouldRejectSemanticRelationsWithoutValidTypeOrEvidence() {
        GraphExtractionResult semanticResult = new GraphExtractionResult(
                List.of(),
                List.of(
                        relation("\u5f20\u4e09", "PERSON", "invented_relation",
                                "\u652f\u4ed8\u7cfb\u7edf", "SYSTEM", "\u5f20\u4e09\u8d1f\u8d23\u652f\u4ed8\u7cfb\u7edf", 0.88),
                        relation("\u5f20\u4e09", "PERSON", "responsible_for",
                                "\u8d22\u52a1\u7cfb\u7edf", "SYSTEM", "\u5f20\u4e09\u8d1f\u8d23\u652f\u4ed8\u7cfb\u7edf", 0.88)
                )
        );
        KnowledgeGraphExtractor hybridExtractor = new KnowledgeGraphExtractor(
                new RagGraphExtractionProperties(),
                List.of((content, seedEntities) -> semanticResult)
        );

        GraphExtractionResult result = hybridExtractor.extract(chunk(
                "\u5f20\u4e09\u8d1f\u8d23\u652f\u4ed8\u7cfb\u7edf\u3002"));

        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .doesNotContain("invented_relation", "responsible_for");
        assertThat(result.entities())
                .extracting(GraphExtractionResult.EntityMention::name)
                .doesNotContain("\u8d22\u52a1\u7cfb\u7edf");
    }

    private KnowledgeChunk chunk(String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent(content);
        return chunk;
    }

    private GraphExtractionResult.RelationMention relation(String source,
                                                           String sourceType,
                                                           String relationType,
                                                           String target,
                                                           String targetType,
                                                           String evidence,
                                                           double confidence) {
        return new GraphExtractionResult.RelationMention(
                mention(source, sourceType, confidence),
                relationType,
                mention(target, targetType, confidence),
                evidence,
                confidence
        );
    }

    private GraphExtractionResult.EntityMention mention(String name, String type, double confidence) {
        return new GraphExtractionResult.EntityMention(
                name,
                normalize(name),
                type,
                List.of(),
                confidence
        );
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Punct}\u3001\uff0c\u3002\uff1b\uff1a\uff01\uff1f\u201c\u201d\u2018\u2019\uff08\uff09\u3010\u3011\u300a\u300b\\[\\]{}]+", "");
    }
}
