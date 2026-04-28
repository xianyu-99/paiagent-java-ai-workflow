package com.paiagent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.paiagent.common.ForbiddenException;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.dto.PublishedWorkflowInfoResponse;
import com.paiagent.dto.WorkflowPublishResponse;
import com.paiagent.engine.EngineSelector;
import com.paiagent.engine.WorkflowExecutor;
import com.paiagent.engine.execution.WorkflowExecutionContextHolder;
import com.paiagent.entity.Workflow;
import com.paiagent.entity.WorkflowPublish;
import com.paiagent.mapper.WorkflowPublishMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowPublishService extends ServiceImpl<WorkflowPublishMapper, WorkflowPublish> {

    private final WorkflowService workflowService;

    private final EngineSelector engineSelector;

    public WorkflowPublishService(WorkflowService workflowService, EngineSelector engineSelector) {
        this.workflowService = workflowService;
        this.engineSelector = engineSelector;
    }

    @Transactional
    public WorkflowPublishResponse publishWorkflow(Long workflowId, Long userId, boolean admin) {
        Workflow workflow = workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        WorkflowPublish publish = findByWorkflowId(workflowId);

        if (publish == null) {
            publish = new WorkflowPublish();
            publish.setWorkflowId(workflowId);
            publish.setShareKey(generateShareKey());
            publish.setCreatedBy(userId);
        }

        publish.setTitle(StringUtils.hasText(workflow.getName()) ? workflow.getName() : "Untitled workflow");
        publish.setDescription(workflow.getDescription());
        publish.setEnabled(true);

        if (publish.getId() == null) {
            this.save(publish);
        } else {
            this.updateById(publish);
        }

        return toResponse(publish);
    }

    @Transactional
    public WorkflowPublishResponse unpublishWorkflow(Long workflowId, Long userId, boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        WorkflowPublish publish = findByWorkflowId(workflowId);
        if (publish == null) {
            return null;
        }
        publish.setEnabled(false);
        this.updateById(publish);
        return toResponse(publish);
    }

    public WorkflowPublishResponse getPublishStatus(Long workflowId, Long userId, boolean admin) {
        workflowService.getAccessibleWorkflow(workflowId, userId, admin);
        WorkflowPublish publish = findByWorkflowId(workflowId);
        return publish == null ? null : toResponse(publish);
    }

    public PublishedWorkflowInfoResponse getPublicWorkflowInfo(String shareKey) {
        WorkflowPublish publish = getEnabledPublish(shareKey);
        Workflow workflow = workflowService.getById(publish.getWorkflowId());
        if (workflow == null) {
            throw new RuntimeException("工作流不存在或已删除");
        }
        return new PublishedWorkflowInfoResponse(
                workflow.getId(),
                publish.getShareKey(),
                publish.getTitle(),
                publish.getDescription(),
                buildNodeSummary(workflow.getFlowData()),
                buildPublicApiPath(publish.getShareKey())
        );
    }

    public ExecutionResponse executePublishedWorkflow(String shareKey, String inputData) throws Exception {
        WorkflowPublish publish = getEnabledPublish(shareKey);
        Workflow workflow = workflowService.getWorkflowForPublishedExecution(publish.getWorkflowId());
        WorkflowExecutor executor = engineSelector.selectEngine(workflow);

        Long executionUserId = workflow.getOwnerId() == null ? publish.getCreatedBy() : workflow.getOwnerId();
        boolean executionAdmin = workflow.getOwnerId() == null;
        WorkflowExecutionContextHolder.set(executionUserId, executionAdmin);
        try {
            return executor.execute(workflow, inputData);
        } finally {
            WorkflowExecutionContextHolder.clear();
        }
    }

    private WorkflowPublish findByWorkflowId(Long workflowId) {
        return this.getOne(new LambdaQueryWrapper<WorkflowPublish>()
                .eq(WorkflowPublish::getWorkflowId, workflowId)
                .last("LIMIT 1"));
    }

    private WorkflowPublish getEnabledPublish(String shareKey) {
        if (!StringUtils.hasText(shareKey)) {
            throw new RuntimeException("发布链接无效");
        }
        WorkflowPublish publish = this.getOne(new LambdaQueryWrapper<WorkflowPublish>()
                .eq(WorkflowPublish::getShareKey, shareKey)
                .eq(WorkflowPublish::getEnabled, true)
                .last("LIMIT 1"));
        if (publish == null) {
            throw new RuntimeException("发布链接不存在或已停用");
        }
        return publish;
    }

    private String generateShareKey() {
        for (int i = 0; i < 5; i++) {
            String shareKey = UUID.randomUUID().toString().replace("-", "");
            Long count = this.count(new LambdaQueryWrapper<WorkflowPublish>()
                    .eq(WorkflowPublish::getShareKey, shareKey));
            if (count == null || count == 0) {
                return shareKey;
            }
        }
        throw new RuntimeException("生成发布链接失败");
    }

    private WorkflowPublishResponse toResponse(WorkflowPublish publish) {
        WorkflowPublishResponse response = new WorkflowPublishResponse();
        BeanUtils.copyProperties(publish, response);
        response.setPublicPagePath("/p/" + publish.getShareKey());
        response.setPublicApiPath(buildPublicApiPath(publish.getShareKey()));
        return response;
    }

    private String buildPublicApiPath(String shareKey) {
        return "/api/published-workflows/" + shareKey + "/execute";
    }

    private String buildNodeSummary(String flowData) {
        if (!StringUtils.hasText(flowData)) {
            return null;
        }

        try {
            JSONObject flow = JSON.parseObject(flowData);
            JSONArray nodes = flow.getJSONArray("nodes");
            if (nodes == null || nodes.isEmpty()) {
                return null;
            }

            Map<String, String> nodeLabels = new LinkedHashMap<>();
            Map<String, Integer> indegree = new HashMap<>();
            Map<String, List<String>> outgoing = new HashMap<>();

            for (int i = 0; i < nodes.size(); i++) {
                JSONObject node = nodes.getJSONObject(i);
                String id = node.getString("id");
                if (!StringUtils.hasText(id)) {
                    continue;
                }
                nodeLabels.put(id, getNodeDisplayName(node));
                indegree.put(id, 0);
                outgoing.put(id, new ArrayList<>());
            }

            JSONArray edges = flow.getJSONArray("edges");
            if (edges != null) {
                for (int i = 0; i < edges.size(); i++) {
                    JSONObject edge = edges.getJSONObject(i);
                    String source = edge.getString("source");
                    String target = edge.getString("target");
                    if (!nodeLabels.containsKey(source) || !nodeLabels.containsKey(target)) {
                        continue;
                    }
                    outgoing.get(source).add(target);
                    indegree.put(target, indegree.getOrDefault(target, 0) + 1);
                }
            }

            List<String> orderedLabels = new ArrayList<>();
            ArrayDeque<String> queue = new ArrayDeque<>();
            for (String nodeId : nodeLabels.keySet()) {
                if (indegree.getOrDefault(nodeId, 0) == 0) {
                    queue.add(nodeId);
                }
            }

            while (!queue.isEmpty()) {
                String nodeId = queue.remove();
                orderedLabels.add(nodeLabels.get(nodeId));
                for (String next : outgoing.getOrDefault(nodeId, List.of())) {
                    int nextIndegree = indegree.getOrDefault(next, 0) - 1;
                    indegree.put(next, nextIndegree);
                    if (nextIndegree == 0) {
                        queue.add(next);
                    }
                }
            }

            if (orderedLabels.isEmpty()) {
                return null;
            }
            return String.join(" -> ", orderedLabels);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String getNodeDisplayName(JSONObject node) {
        JSONObject data = node.getJSONObject("data");
        String type = data == null ? null : data.getString("type");
        if (!StringUtils.hasText(type)) {
            type = node.getString("type");
        }

        if (!StringUtils.hasText(type)) {
            return "Node";
        }

        return switch (type.toLowerCase()) {
            case "input" -> "Input";
            case "output" -> "Output";
            case "llm", "openai", "deepseek", "qwen", "zhipu", "glm", "step", "aiping" -> "LLM";
            case "rag" -> "RAG";
            case "tts" -> "TTS";
            case "condition" -> "条件分支";
            case "loop" -> "循环";
            default -> {
                if (data != null && StringUtils.hasText(data.getString("label"))) {
                    yield data.getString("label");
                }
                yield type;
            }
        };
    }
}
