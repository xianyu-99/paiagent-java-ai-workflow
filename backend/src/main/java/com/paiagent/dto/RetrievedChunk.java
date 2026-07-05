package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedChunk {

    private Long chunkId;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private String sourceName;

    private String sectionTitle;

    private Integer pageNumber;

    private Double score;

    private Double vectorScore;

    private Double keywordScore;

    private Integer rank;

    private List<String> matchedTerms;

    private List<String> graphEvidence;

    private String contextContent;

    private List<Integer> contextChunkIndexes;

    private Double personalizationScore;

    private List<String> personalizationReasons;
}
