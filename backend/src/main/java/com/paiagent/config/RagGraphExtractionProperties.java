package com.paiagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "paiagent.rag.graph.extraction")
public class RagGraphExtractionProperties {

    private boolean dictionaryEnabled = true;

    /**
     * Format: canonicalName|ENTITY_TYPE|alias1,alias2
     */
    private List<String> dictionaryEntries = new ArrayList<>();

    private boolean llmEnabled = false;

    private String llmProvider = "local_qwen";

    private String llmBaseUrl = "http://127.0.0.1:11434/v1";

    private String llmApiKey = "ollama";

    private String llmModel = "qwen2.5:7b";

    private double llmTemperature = 0.1d;

    private int maxChunkChars = 3000;

    private int maxLlmEntities = 24;

    private int maxLlmRelations = 32;

    private double minLlmConfidence = 0.62d;
}
