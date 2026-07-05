package com.paiagent.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.paiagent.dto.RetrievedChunk;
import com.paiagent.entity.UserRetrievalProfile;
import com.paiagent.mapper.UserRetrievalProfileMapper;
import com.paiagent.service.rag.RagRetrievalScorer;
import com.paiagent.service.rag.RetrievalPersonalization;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class UserRetrievalProfileService {

    private static final int MAX_PROFILE_TERMS = 40;

    private final UserRetrievalProfileMapper mapper;
    private final RagRetrievalScorer scorer;

    public UserRetrievalProfileService(UserRetrievalProfileMapper mapper,
                                       RagRetrievalScorer scorer) {
        this.mapper = mapper;
        this.scorer = scorer;
    }

    public RetrievalPersonalization buildProfile(Long userId, String query) {
        if (userId == null) {
            return new RetrievalPersonalization(null, Map.of());
        }

        Map<String, Double> weights = new LinkedHashMap<>();
        UserRetrievalProfile profile = findByUserId(userId);
        if (profile != null) {
            weights.putAll(parseWeights(profile.getProfileJson()));
        }

        for (String term : extractTerms(query)) {
            weights.merge(term, 0.8d, Double::sum);
        }

        return new RetrievalPersonalization(userId, trimWeights(weights));
    }

    public void recordInteraction(Long userId, String query, List<RetrievedChunk> chunks) {
        if (userId == null || !StringUtils.hasText(query)) {
            return;
        }
        try {
            UserRetrievalProfile profile = findByUserId(userId);
            Map<String, Double> weights = profile == null
                    ? new LinkedHashMap<>()
                    : parseWeights(profile.getProfileJson());

            decay(weights);
            addTerms(weights, extractTerms(query), 1.4d);

            if (chunks != null) {
                chunks.stream().limit(3).forEach(chunk -> {
                    addTerms(weights, chunk.getMatchedTerms(), 0.5d);
                    addTerms(weights, extractTerms(chunk.getSourceName()), 0.25d);
                    addTerms(weights, extractTerms(chunk.getSectionTitle()), 0.25d);
                    if (chunk.getPersonalizationReasons() != null) {
                        addTerms(weights, chunk.getPersonalizationReasons().stream()
                                .map(reason -> reason.replace("profile:", ""))
                                .toList(), 0.15d);
                    }
                });
            }

            Map<String, Double> trimmed = trimWeights(weights);
            if (trimmed.isEmpty()) {
                return;
            }

            if (profile == null) {
                profile = new UserRetrievalProfile();
                profile.setUserId(userId);
                profile.setInteractionCount(0);
            }
            profile.setProfileJson(JSON.toJSONString(trimmed));
            profile.setLastQuery(abbreviate(query, 1000));
            profile.setInteractionCount((profile.getInteractionCount() == null ? 0 : profile.getInteractionCount()) + 1);

            if (profile.getId() == null) {
                mapper.insert(profile);
            } else {
                mapper.updateById(profile);
            }
        } catch (Exception e) {
            log.warn("Failed to update user retrieval profile: {}", e.getMessage());
        }
    }

    private UserRetrievalProfile findByUserId(Long userId) {
        return mapper.selectOne(new LambdaQueryWrapper<UserRetrievalProfile>()
                .eq(UserRetrievalProfile::getUserId, userId)
                .last("LIMIT 1"));
    }

    private List<String> extractTerms(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return scorer.searchTerms(text).stream()
                .map(String::trim)
                .filter(term -> term.length() >= 2)
                .distinct()
                .limit(12)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> parseWeights(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = JSON.parseObject(json, Map.class);
            if (!(parsed instanceof Map<?, ?> raw)) {
                return new LinkedHashMap<>();
            }
            Map<String, Double> weights = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = entry.getKey().toString().trim();
                if (!StringUtils.hasText(key)) {
                    continue;
                }
                Object value = entry.getValue();
                double weight = value instanceof Number number
                        ? number.doubleValue()
                        : Double.parseDouble(value.toString());
                if (Double.isFinite(weight) && weight > 0.0d) {
                    weights.put(key, weight);
                }
            }
            return weights;
        } catch (Exception e) {
            log.debug("Ignoring invalid user retrieval profile JSON: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private void decay(Map<String, Double> weights) {
        List<String> keys = new ArrayList<>(weights.keySet());
        for (String key : keys) {
            double decayed = weights.getOrDefault(key, 0.0d) * 0.90d;
            if (decayed < 0.05d) {
                weights.remove(key);
            } else {
                weights.put(key, decayed);
            }
        }
    }

    private void addTerms(Map<String, Double> weights, List<String> terms, double increment) {
        if (terms == null || terms.isEmpty()) {
            return;
        }
        for (String term : terms) {
            if (!StringUtils.hasText(term)) {
                continue;
            }
            weights.merge(term.trim(), increment, Double::sum);
        }
    }

    private Map<String, Double> trimWeights(Map<String, Double> weights) {
        return weights.entrySet().stream()
                .filter(entry -> StringUtils.hasText(entry.getKey()))
                .filter(entry -> entry.getValue() != null && entry.getValue() > 0.0d)
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(MAX_PROFILE_TERMS)
                .collect(LinkedHashMap::new,
                        (map, entry) -> map.put(entry.getKey(), round(entry.getValue())),
                        LinkedHashMap::putAll);
    }

    private double round(double value) {
        return Math.round(value * 1000.0d) / 1000.0d;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
