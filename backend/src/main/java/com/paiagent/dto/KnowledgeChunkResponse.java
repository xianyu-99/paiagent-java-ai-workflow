package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunkResponse {

    private Long id;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private String sourceName;

    private String contentType;

    private String sectionTitle;

    private Integer pageNumber;

    private Integer startOffset;

    private Integer endOffset;

    private Integer tokenCount;

    private LocalDateTime createdAt;
}
