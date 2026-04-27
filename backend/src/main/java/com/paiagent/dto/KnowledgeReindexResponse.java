package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeReindexResponse {

    private Long knowledgeBaseId;

    private Integer chunkCount;

    private String embeddingProvider;

    private String embeddingModel;

    private Integer embeddingDimension;
}
