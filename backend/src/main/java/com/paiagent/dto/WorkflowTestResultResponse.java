package com.paiagent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class WorkflowTestResultResponse {

    private Long id;

    private Long runId;

    private Long caseId;

    private String caseName;

    private String status;

    private String actualOutput;

    private List<Map<String, Object>> assertionResults;

    private Long executionId;

    private Integer duration;

    private String errorMessage;

    private LocalDateTime createdAt;
}
