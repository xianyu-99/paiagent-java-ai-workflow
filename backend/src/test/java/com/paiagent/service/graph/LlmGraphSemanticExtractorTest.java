package com.paiagent.service.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmGraphSemanticExtractorTest {

    @Test
    void shouldParseJsonResponseFromLlm() {
        String raw = """
                ```json
                {
                  "entities": [
                    {"name":"\u5f20\u4e09","type":"PERSON","aliases":[],"confidence":0.91},
                    {"name":"\u652f\u4ed8\u7cfb\u7edf","type":"SYSTEM","aliases":["Payment Service"],"confidence":0.89}
                  ],
                  "relations": [
                    {
                      "source":"\u5f20\u4e09",
                      "sourceType":"PERSON",
                      "relationType":"responsible_for",
                      "target":"\u652f\u4ed8\u7cfb\u7edf",
                      "targetType":"SYSTEM",
                      "evidence":"\u5f20\u4e09\u8d1f\u8d23\u652f\u4ed8\u7cfb\u7edf",
                      "confidence":0.87
                    }
                  ]
                }
                ```
                """;

        GraphExtractionResult result = LlmGraphSemanticExtractor.parseJsonResponse(raw);

        assertThat(result.entities())
                .extracting(GraphExtractionResult.EntityMention::entityType)
                .contains("PERSON", "SYSTEM");
        assertThat(result.relations())
                .extracting(GraphExtractionResult.RelationMention::relationType)
                .containsExactly("responsible_for");
    }

    @Test
    void shouldReturnEmptyResultForInvalidJson() {
        GraphExtractionResult result = LlmGraphSemanticExtractor.parseJsonResponse("not json");

        assertThat(result.entities()).isEmpty();
        assertThat(result.relations()).isEmpty();
    }
}
