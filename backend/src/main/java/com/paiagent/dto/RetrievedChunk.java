package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {

    private Long chunkId;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private Double score;
}
