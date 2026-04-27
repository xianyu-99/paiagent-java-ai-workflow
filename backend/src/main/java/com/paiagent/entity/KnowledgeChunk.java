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
@TableName("knowledge_chunk")
public class KnowledgeChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long knowledgeBaseId;

    private Long documentId;

    private Integer chunkIndex;

    private String content;

    private String sourceName;

    private String contentType;

    private String sectionTitle;

    private Integer pageNumber;

    private Integer startOffset;

    private Integer endOffset;

    private String embedding;

    private String embeddingProvider;

    private String embeddingModel;

    private Integer embeddingDimension;

    private Integer tokenCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
