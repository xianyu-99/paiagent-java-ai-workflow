package com.paiagent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

@Data
public class WorkflowTestCaseRequest {

    @NotBlank(message = "测试用例名称不能为空")
    private String name;

    @NotBlank(message = "测试输入不能为空")
    private String inputData;

    private List<String> expectedContains;

    private List<String> expectedNotContains;

    private String expectedStatus;

    private Boolean requireCitation;

    private Boolean requireAudio;

    private Integer maxDurationMs;

    private Boolean enabled;
}
