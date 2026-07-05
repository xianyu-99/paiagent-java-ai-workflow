package com.paiagent.service.graph;

import java.util.List;

public record GraphExtractionResult(
        List<EntityMention> entities,
        List<RelationMention> relations
) {

    public record EntityMention(
            String name,
            String normalizedName,
            String entityType,
            List<String> aliases,
            double confidence
    ) {
    }

    public record RelationMention(
            EntityMention source,
            String relationType,
            EntityMention target,
            String evidence,
            double confidence
    ) {
    }
}
