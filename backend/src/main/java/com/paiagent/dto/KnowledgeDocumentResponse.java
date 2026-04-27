package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentResponse {

    private Long id;

    private Long knowledgeBaseId;

    private String fileName;

    private String contentType;

    private String parserType;

    private Integer chunkCount;

    private LocalDateTime createdAt;
}
