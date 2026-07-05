package com.paiagent.service.graph;

public record GraphEvidence(
        Long relationId,
        Long chunkId,
        Long documentId,
        String sourceName,
        String sourceType,
        String relationType,
        String targetName,
        String targetType,
        String evidence,
        Double confidence,
        int hop
) {

    public String toContextLine() {
        return "%s --[%s]--> %s (hop=%d, confidence=%.2f, evidence=%s)".formatted(
                sourceName,
                relationType,
                targetName,
                hop,
                confidence == null ? 0.0 : confidence,
                evidence == null ? "" : evidence
        );
    }
}
