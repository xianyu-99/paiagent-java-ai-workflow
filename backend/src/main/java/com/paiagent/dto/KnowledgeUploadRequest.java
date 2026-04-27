package com.paiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KnowledgeUploadRequest {

    private String fileName;

    @NotBlank(message = "文档内容不能为空")
    private String content;
}
