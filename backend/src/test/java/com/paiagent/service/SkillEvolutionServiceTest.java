package com.paiagent.service;

import com.paiagent.entity.SkillEvolutionRecord;
import com.paiagent.mapper.SkillEvolutionRecordMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SkillEvolutionServiceTest {

    @Test
    void shouldRecordPendingReviewCandidate() {
        SkillEvolutionRecordMapper mapper = mock(SkillEvolutionRecordMapper.class);
        SkillEvolutionService service = new SkillEvolutionService(mapper);

        SkillEvolutionRecord record = service.recordCandidate(
                "service-desk-answer",
                "AGENT_EXECUTION",
                10L,
                "LOW_CONFIDENCE",
                "Answer missed citation.",
                "Add citation check rule."
        );

        assertThat(record.getSkillName()).isEqualTo("service-desk-answer");
        assertThat(record.getStatus()).isEqualTo(SkillEvolutionService.STATUS_PENDING_REVIEW);
        assertThat(record.getFeedbackType()).isEqualTo("LOW_CONFIDENCE");
        verify(mapper).insert(any(SkillEvolutionRecord.class));
    }

    @Test
    void shouldRejectBlankSkillName() {
        SkillEvolutionService service = new SkillEvolutionService(mock(SkillEvolutionRecordMapper.class));

        assertThrows(IllegalArgumentException.class,
                () -> service.recordCandidate("", null, null, null, null, null));
    }
}
