package com.paiagent.service.document;

import java.util.List;

public record ParsedDocument(
        String fileName,
        String contentType,
        String parserType,
        String rawText,
        List<ParsedSegment> segments
) {
}
