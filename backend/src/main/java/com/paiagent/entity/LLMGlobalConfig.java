package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 全局 LLM 配置实体类
 */
@Data
@TableName("llm_global_config")
public class LLMGlobalConfig {

    /**
     * 配置主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 提供商: openai/deepseek/qwen
     */
    private String provider;

    /**
     * 配置名称
     */
    private String configName;

    /**
     * API 地址
     */
    private String apiUrl;

    /**
     * API 密钥
     */
    private String apiKey;

    /**
     * 默认模型
     */
    private String model;

    /**
     * 默认温度
     */
    private BigDecimal temperature;

    /**
     * 是否默认配置(0-否,1-是)
     */
    private Integer isDefault;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /**
     * 逻辑删除标识
     */
    @TableLogic
    private Integer deleted;
}
