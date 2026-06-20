package com.paiagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * knowledge_chunk 表轻量迁移：为已有的数据库添加 FULLTEXT 索引，
 * 使 MATCH...AGAINST 关键词检索可以正常工作。
 * <p>
 * 使用 ngram parser 以兼容中文分词场景。
 */
@Slf4j
@Component
public class KnowledgeChunkMigrationRunner implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeChunkMigrationRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!tableExists()) {
            log.warn("knowledge_chunk 表不存在，跳过 FULLTEXT 索引迁移");
            return;
        }
        addFullTextIndexIfMissing();
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'knowledge_chunk'
                """, Integer.class);
        return count != null && count > 0;
    }

    private void addFullTextIndexIfMissing() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'knowledge_chunk'
                  AND index_name = 'ft_chunk_content'
                """, Integer.class);

        if (count != null && count > 0) {
            log.info("knowledge_chunk.ft_chunk_content FULLTEXT 索引已存在，跳过");
            return;
        }

        String sql = """
                ALTER TABLE knowledge_chunk
                ADD FULLTEXT INDEX ft_chunk_content (content, source_name, section_title)
                WITH PARSER ngram
                """;
        log.info("执行 knowledge_chunk FULLTEXT 索引迁移: {}", sql.stripIndent().trim());
        jdbcTemplate.execute(sql);
        log.info("knowledge_chunk.ft_chunk_content FULLTEXT 索引创建完成");
    }
}
