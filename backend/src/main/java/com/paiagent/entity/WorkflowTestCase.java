package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("workflow_test_case")
public class WorkflowTestCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private String name;

    private String inputData;

    private String expectedContains;

    private String expectedNotContains;

    private String expectedStatus;

    private Boolean requireCitation;

    private Boolean requireAudio;

    private Integer maxDurationMs;

    private Boolean enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
