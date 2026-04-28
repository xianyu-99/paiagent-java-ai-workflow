package com.paiagent.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class WorkflowPublishResponse {

    private Long id;

    private Long workflowId;

    private String shareKey;

    private String apiAccessKey;

    private String title;

    private String description;

    private Boolean enabled;

    private String publicPagePath;

    private String publicApiPath;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
