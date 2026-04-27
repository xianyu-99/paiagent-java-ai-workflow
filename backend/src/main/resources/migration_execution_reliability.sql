-- 执行可靠性基础版字段迁移
-- 旧库可手动执行；应用启动时也会自动做轻量迁移。

ALTER TABLE execution_record
    ADD COLUMN error_log JSON NULL COMMENT '结构化错误日志';

ALTER TABLE execution_record
    ADD COLUMN retry_count INT DEFAULT 0 COMMENT '重试次数';

ALTER TABLE execution_record
    ADD COLUMN timeout_count INT DEFAULT 0 COMMENT '超时次数';
