package com.paiagent.service.graph;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.toolkit.Db;
import com.paiagent.entity.KnowledgeChunk;
import com.paiagent.entity.KnowledgeGraphEntity;
import com.paiagent.entity.KnowledgeGraphRelation;
import com.paiagent.mapper.KnowledgeGraphEntityMapper;
import com.paiagent.mapper.KnowledgeGraphRelationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class KnowledgeGraphService {

    private static final int MAX_QUERY_TERMS = 8;

    private final KnowledgeGraphEntityMapper entityMapper;
    private final KnowledgeGraphRelationMapper relationMapper;
    private final KnowledgeGraphExtractor extractor;

    public KnowledgeGraphService(KnowledgeGraphEntityMapper entityMapper,
                                 KnowledgeGraphRelationMapper relationMapper,
                                 KnowledgeGraphExtractor extractor) {
        this.entityMapper = entityMapper;
        this.relationMapper = relationMapper;
        this.extractor = extractor;
    }

    public void rebuildDocumentGraph(Long knowledgeBaseId, Long documentId, List<KnowledgeChunk> chunks) {
        if (knowledgeBaseId == null || documentId == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        deleteDocumentGraph(documentId);

        List<KnowledgeGraphEntity> entities = new ArrayList<>();
        List<KnowledgeGraphRelation> relations = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            GraphExtractionResult result = extractor.extract(chunk);
            for (GraphExtractionResult.EntityMention mention : result.entities()) {
                entities.add(toEntity(knowledgeBaseId, documentId, chunk.getId(), mention));
            }
            for (GraphExtractionResult.RelationMention mention : result.relations()) {
                relations.add(toRelation(knowledgeBaseId, documentId, chunk.getId(), mention));
            }
        }

        if (!entities.isEmpty()) {
            Db.saveBatch(entities);
        }
        if (!relations.isEmpty()) {
            Db.saveBatch(relations);
        }
        log.info("Knowledge graph rebuilt: kbId={}, documentId={}, entities={}, relations={}",
                knowledgeBaseId, documentId, entities.size(), relations.size());
    }

    public void deleteDocumentGraph(Long documentId) {
        if (documentId == null) {
            return;
        }
        entityMapper.delete(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                .eq(KnowledgeGraphEntity::getDocumentId, documentId));
        relationMapper.delete(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                .eq(KnowledgeGraphRelation::getDocumentId, documentId));
    }

    public void deleteKnowledgeBaseGraph(Long knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        entityMapper.delete(new LambdaQueryWrapper<KnowledgeGraphEntity>()
                .eq(KnowledgeGraphEntity::getKnowledgeBaseId, knowledgeBaseId));
        relationMapper.delete(new LambdaQueryWrapper<KnowledgeGraphRelation>()
                .eq(KnowledgeGraphRelation::getKnowledgeBaseId, knowledgeBaseId));
    }

    public List<GraphEvidence> findEvidence(Long knowledgeBaseId,
                                            String query,
                                            Collection<Long> candidateChunkIds,
                                            int maxRelations) {
        if (knowledgeBaseId == null || maxRelations <= 0) {
            return List.of();
        }

        Set<String> queryEntityNames = queryEntityNames(knowledgeBaseId, query);
        Set<Long> chunkIds = candidateChunkIds == null
                ? Set.of()
                : candidateChunkIds.stream()
                .filter(id -> id != null && id > 0)
                .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll);

        List<KnowledgeGraphRelation> firstHop = searchRelations(knowledgeBaseId, queryEntityNames, chunkIds, maxRelations);
        if (firstHop.isEmpty()) {
            return List.of();
        }

        Map<String, GraphEvidence> evidence = new LinkedHashMap<>();
        for (KnowledgeGraphRelation relation : firstHop) {
            GraphEvidence graphEvidence = toEvidence(relation, 1);
            evidence.put(evidenceKey(graphEvidence), graphEvidence);
        }

        Set<String> expandedNames = new LinkedHashSet<>();
        for (KnowledgeGraphRelation relation : firstHop) {
            expandedNames.add(relation.getSourceNormalizedName());
            expandedNames.add(relation.getTargetNormalizedName());
        }
        expandedNames.removeAll(queryEntityNames);

        int remaining = maxRelations - evidence.size();
        if (remaining > 0 && !expandedNames.isEmpty()) {
            List<KnowledgeGraphRelation> secondHop = searchRelations(
                    knowledgeBaseId,
                    limitNames(expandedNames, MAX_QUERY_TERMS),
                    Set.of(),
                    remaining
            );
            for (KnowledgeGraphRelation relation : secondHop) {
                GraphEvidence graphEvidence = toEvidence(relation, 2);
                evidence.putIfAbsent(evidenceKey(graphEvidence), graphEvidence);
                if (evidence.size() >= maxRelations) {
                    break;
                }
            }
        }

        return new ArrayList<>(evidence.values());
    }

    private List<KnowledgeGraphRelation> searchRelations(Long knowledgeBaseId,
                                                         Set<String> normalizedNames,
                                                         Set<Long> chunkIds,
                                                         int limit) {
        QueryWrapper<KnowledgeGraphRelation> wrapper = new QueryWrapper<>();
        wrapper.eq("knowledge_base_id", knowledgeBaseId);
        wrapper.eq("deleted", 0);

        boolean hasNames = normalizedNames != null && !normalizedNames.isEmpty();
        boolean hasChunks = chunkIds != null && !chunkIds.isEmpty();
        if (!hasNames && !hasChunks) {
            return List.of();
        }

        wrapper.and(nested -> {
            boolean first = true;
            if (hasChunks) {
                nested.in("chunk_id", chunkIds);
                first = false;
            }
            if (hasNames) {
                for (String name : normalizedNames) {
                    if (!StringUtils.hasText(name)) {
                        continue;
                    }
                    if (!first) {
                        nested.or();
                    }
                    nested.eq("source_normalized_name", name)
                            .or()
                            .eq("target_normalized_name", name);
                    first = false;
                }
            }
        });
        wrapper.orderByDesc("confidence");
        wrapper.last("LIMIT " + Math.max(1, limit));
        return relationMapper.selectList(wrapper);
    }

    private Set<String> queryEntityNames(Long knowledgeBaseId, String query) {
        Set<String> names = new LinkedHashSet<>();
        for (GraphExtractionResult.EntityMention mention : extractor.matchEntities(query)) {
            names.add(mention.normalizedName());
            if (names.size() >= MAX_QUERY_TERMS) {
                return names;
            }
        }

        if (StringUtils.hasText(query)) {
            String normalized = extractor.normalize(query);
            for (KnowledgeGraphEntity entity : entityMapper.selectList(new QueryWrapper<KnowledgeGraphEntity>()
                    .eq("knowledge_base_id", knowledgeBaseId)
                    .eq("deleted", 0)
                    .last("LIMIT 200"))) {
                if (normalized.contains(entity.getNormalizedName())) {
                    names.add(entity.getNormalizedName());
                    if (names.size() >= MAX_QUERY_TERMS) {
                        break;
                    }
                }
            }
        }
        return names;
    }

    private Set<String> limitNames(Set<String> names, int limit) {
        Set<String> limited = new LinkedHashSet<>();
        for (String name : names) {
            limited.add(name);
            if (limited.size() >= limit) {
                break;
            }
        }
        return limited;
    }

    private KnowledgeGraphEntity toEntity(Long knowledgeBaseId,
                                          Long documentId,
                                          Long chunkId,
                                          GraphExtractionResult.EntityMention mention) {
        KnowledgeGraphEntity entity = new KnowledgeGraphEntity();
        entity.setKnowledgeBaseId(knowledgeBaseId);
        entity.setDocumentId(documentId);
        entity.setChunkId(chunkId);
        entity.setName(mention.name());
        entity.setNormalizedName(mention.normalizedName());
        entity.setEntityType(mention.entityType());
        entity.setAliases(String.join("|", mention.aliases()));
        entity.setConfidence(mention.confidence());
        return entity;
    }

    private KnowledgeGraphRelation toRelation(Long knowledgeBaseId,
                                              Long documentId,
                                              Long chunkId,
                                              GraphExtractionResult.RelationMention mention) {
        KnowledgeGraphRelation relation = new KnowledgeGraphRelation();
        relation.setKnowledgeBaseId(knowledgeBaseId);
        relation.setDocumentId(documentId);
        relation.setChunkId(chunkId);
        relation.setSourceName(mention.source().name());
        relation.setSourceNormalizedName(mention.source().normalizedName());
        relation.setSourceType(mention.source().entityType());
        relation.setRelationType(mention.relationType());
        relation.setTargetName(mention.target().name());
        relation.setTargetNormalizedName(mention.target().normalizedName());
        relation.setTargetType(mention.target().entityType());
        relation.setEvidence(mention.evidence());
        relation.setConfidence(mention.confidence());
        return relation;
    }

    private GraphEvidence toEvidence(KnowledgeGraphRelation relation, int hop) {
        return new GraphEvidence(
                relation.getId(),
                relation.getChunkId(),
                relation.getDocumentId(),
                relation.getSourceName(),
                relation.getSourceType(),
                relation.getRelationType(),
                relation.getTargetName(),
                relation.getTargetType(),
                relation.getEvidence(),
                relation.getConfidence(),
                hop
        );
    }

    private String evidenceKey(GraphEvidence evidence) {
        return evidence.sourceName()
                + "|"
                + evidence.relationType()
                + "|"
                + evidence.targetName()
                + "|"
                + evidence.chunkId();
    }
}
