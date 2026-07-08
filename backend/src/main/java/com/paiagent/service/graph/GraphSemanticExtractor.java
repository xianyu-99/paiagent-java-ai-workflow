package com.paiagent.service.graph;

import java.util.List;

public interface GraphSemanticExtractor {

    GraphExtractionResult extract(String content, List<GraphExtractionResult.EntityMention> seedEntities);
}
