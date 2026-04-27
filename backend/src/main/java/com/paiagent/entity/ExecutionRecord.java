package com.paiagent.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 执行记录实体类
 */
@Data
@TableName("execution_record")
public class ExecutionRecord {
    
    /**
     * 执行记录主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * 工作流 ID
     */
    private Long flowId;
    
    /**
     * 输入数据 - JSON 格式
     */
    private String inputData;
    
    /**
     * 输出数据 - JSON 格式
     */
    private String outputData;
    
    /**
     * 执行状态(SUCCESS/FAILED)
     */
    private String status;
    
    /**
     * 每个节点的执行结果 - JSON 格式
     */
    private String nodeResults;
    
    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 结构化错误日志 - JSON 格式
     */
    private String errorLog;

    /**
     * 本次执行触发的重试次数
     */
    private Integer retryCount;

    /**
     * 本次执行触发的超时次数
     */
    private Integer timeoutCount;
    
    /**
     * 执行耗时(毫秒)
     */
    private Integer duration;
    
    /**
     * 执行时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime executedAt;
    
    /**
     * 逻辑删除标识
     */
    @TableLogic
    private Integer deleted;
}
