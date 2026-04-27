package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeImportTaskResponse {

    private Long id;

    private Long knowledgeBaseId;

    private Long documentId;

    private String fileName;

    private String contentType;

    private String status;

    private String stage;

    private Integer progress;

    private Integer totalChunks;

    private Integer processedChunks;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
