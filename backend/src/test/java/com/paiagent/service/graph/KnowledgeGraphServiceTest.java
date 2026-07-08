package com.paiagent.service.graph;

import com.paiagent.entity.KnowledgeGraphEntity;
import com.paiagent.entity.KnowledgeGraphRelation;
import com.paiagent.mapper.KnowledgeGraphEntityMapper;
import com.paiagent.mapper.KnowledgeGraphRelationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeGraphServiceTest {

    @Test
    void shouldRouteGlobalQueryAndMatchEntityAliases() {
        KnowledgeGraphEntityMapper entityMapper = mock(KnowledgeGraphEntityMapper.class);
        KnowledgeGraphRelationMapper relationMapper = mock(KnowledgeGraphRelationMapper.class);
        KnowledgeGraphService service = new KnowledgeGraphService(
                entityMapper,
                relationMapper,
                new KnowledgeGraphExtractor()
        );

        KnowledgeGraphEntity it = entity("IT部门", "it部门", "信息技术部|IT支持");
        KnowledgeGraphRelation relation = relation(
                "IT部门",
                "it部门",
                "DEPARTMENT",
                "handled_by",
                "VPN开通流程",
                "vpn开通流程",
                "PROCESS",
                "VPN开通流程由IT部门处理"
        );

        when(entityMapper.selectList(any())).thenReturn(List.of(it));
        when(relationMapper.selectList(any())).thenReturn(List.of(relation));

        List<GraphEvidence> evidence = service.findEvidence(
                1L,
                "信息技术部有哪些流程",
                List.of(),
                5
        );

        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).hop()).isZero();
        assertThat(evidence.get(0).sourceName()).isEqualTo("IT部门");
        assertThat(evidence.get(0).targetName()).isEqualTo("VPN开通流程");
    }

    private KnowledgeGraphEntity entity(String name, String normalizedName, String aliases) {
        KnowledgeGraphEntity entity = new KnowledgeGraphEntity();
        entity.setName(name);
        entity.setNormalizedName(normalizedName);
        entity.setEntityType("DEPARTMENT");
        entity.setAliases(aliases);
        entity.setConfidence(0.9d);
        return entity;
    }

    private KnowledgeGraphRelation relation(String sourceName,
                                            String sourceNormalizedName,
                                            String sourceType,
                                            String relationType,
                                            String targetName,
                                            String targetNormalizedName,
                                            String targetType,
                                            String evidence) {
        KnowledgeGraphRelation relation = new KnowledgeGraphRelation();
        relation.setId(10L);
        relation.setKnowledgeBaseId(1L);
        relation.setChunkId(20L);
        relation.setDocumentId(30L);
        relation.setSourceName(sourceName);
        relation.setSourceNormalizedName(sourceNormalizedName);
        relation.setSourceType(sourceType);
        relation.setRelationType(relationType);
        relation.setTargetName(targetName);
        relation.setTargetNormalizedName(targetNormalizedName);
        relation.setTargetType(targetType);
        relation.setEvidence(evidence);
        relation.setConfidence(0.9d);
        return relation;
    }
}
