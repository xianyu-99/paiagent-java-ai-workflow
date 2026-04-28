package com.paiagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PublishedWorkflowInfoResponse {

    private Long workflowId;

    private String shareKey;

    private String title;

    private String description;

    private String nodeSummary;

    private String publicApiPath;
}
