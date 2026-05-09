package com.paiagent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkflowTestRunResponse {

    private Long id;

    private Long workflowId;

    private String status;

    private Integer totalCount;

    private Integer passedCount;

    private Integer failedCount;

    private Integer duration;

    private Long createdBy;

    private List<WorkflowTestResultResponse> results;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
