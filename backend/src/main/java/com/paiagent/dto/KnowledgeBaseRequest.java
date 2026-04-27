package com.paiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeBaseRequest {

    @NotBlank(message = "知识库名称不能为空")
    private String name;

    private String description;
}
