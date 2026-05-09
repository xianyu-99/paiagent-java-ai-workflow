package com.paiagent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkflowTestCaseResponse {

    private Long id;

    private Long workflowId;

    private String name;

    private String inputData;

    private List<String> expectedContains;

    private List<String> expectedNotContains;

    private String expectedStatus;

    private Boolean requireCitation;

    private Boolean requireAudio;

    private Integer maxDurationMs;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
