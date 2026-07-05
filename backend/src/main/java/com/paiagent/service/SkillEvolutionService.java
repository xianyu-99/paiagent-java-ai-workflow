package com.paiagent.service;

import com.paiagent.entity.SkillEvolutionRecord;
import com.paiagent.mapper.SkillEvolutionRecordMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SkillEvolutionService {

    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";

    private final SkillEvolutionRecordMapper mapper;

    public SkillEvolutionService(SkillEvolutionRecordMapper mapper) {
        this.mapper = mapper;
    }

    public SkillEvolutionRecord recordCandidate(
            String skillName,
            String sourceType,
            Long sourceId,
            String feedbackType,
            String feedbackSummary,
            String proposedPatch
    ) {
        if (!StringUtils.hasText(skillName)) {
            throw new IllegalArgumentException("skillName is required");
        }
        SkillEvolutionRecord record = new SkillEvolutionRecord();
        record.setSkillName(skillName.trim());
        record.setSourceType(defaultString(sourceType, "AGENT_EXECUTION"));
        record.setSourceId(sourceId);
        record.setFeedbackType(defaultString(feedbackType, "QUALITY_ISSUE"));
        record.setFeedbackSummary(trimTo(feedbackSummary, 2000));
        record.setProposedPatch(trimTo(proposedPatch, 4000));
        record.setStatus(STATUS_PENDING_REVIEW);
        mapper.insert(record);
        return record;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String trimTo(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }
}
