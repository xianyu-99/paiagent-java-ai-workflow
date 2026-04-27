package com.paiagent.service.document;

public record ParsedSegment(
        String text,
        String sourceName,
        String contentType,
        String sectionTitle,
        Integer pageNumber,
        Integer startOffset,
        Integer endOffset
) {
}
