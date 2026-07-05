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
@TableName("knowledge_graph_relation")
public class KnowledgeGraphRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;

    private Long documentId;

    private Long chunkId;

    private String sourceName;

    private String sourceNormalizedName;

    private String sourceType;

    private String relationType;

    private String targetName;

    private String targetNormalizedName;

    private String targetType;

    private String evidence;

    private Double confidence;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
