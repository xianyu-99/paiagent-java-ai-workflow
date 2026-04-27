package com.paiagent.controller;

import com.paiagent.common.AuthContext;
import com.paiagent.common.ForbiddenException;
import com.paiagent.common.Result;
import com.paiagent.dto.ExecutionEvent;
import com.paiagent.dto.ExecutionRequest;
import com.paiagent.dto.ExecutionResponse;
import com.paiagent.engine.EngineSelector;
import com.paiagent.engine.WorkflowExecutor;
import com.paiagent.engine.execution.WorkflowExecutionContextHolder;
import com.paiagent.entity.Workflow;
import com.paiagent.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Slf4j
@Tag(name = "工作流执行接口")
@RestController
@RequestMapping("/api/workflows")
public class ExecutionController {

    private static final Duration STREAM_TICKET_TTL = Duration.ofSeconds(60);
    private static final String STREAM_TICKET_PREFIX = "workflow:stream-ticket:";

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private EngineSelector engineSelector;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    @Qualifier("workflowExecutionTaskExecutor")
    private ThreadPoolTaskExecutor workflowExecutionTaskExecutor;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    @Operation(summary = "执行工作流")
    @PostMapping("/{id}/execute")
    public Result<ExecutionResponse> executeWorkflow(
            @PathVariable Long id,
            @Valid @RequestBody ExecutionRequest request,
            HttpServletRequest servletRequest
    ) {
        try {
            Long userId = AuthContext.getUserId(servletRequest);
            boolean admin = AuthContext.isAdmin(servletRequest);
            Workflow workflow = workflowService.getAccessibleWorkflow(
                    id,
                    userId,
                    admin
            );
            WorkflowExecutor executor = engineSelector.selectEngine(workflow);
            WorkflowExecutionContextHolder.set(userId, admin);
            try {
                ExecutionResponse response = executor.execute(workflow, request.getInputData());
                return Result.success(response);
            } finally {
                WorkflowExecutionContextHolder.clear();
            }
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (Exception e) {
            return Result.error("工作流执行失败: " + e.getMessage());
        }
    }

    @Operation(summary = "创建实时执行票据")
    @PostMapping("/{id}/execute/stream-ticket")
    public Result<StreamTicketResponse> createStreamTicket(@PathVariable Long id, HttpServletRequest servletRequest) {
        Long userId = AuthContext.getUserId(servletRequest);
        boolean admin = AuthContext.isAdmin(servletRequest);

        try {
            workflowService.getAccessibleWorkflow(id, userId, admin);
            String ticket = UUID.randomUUID().toString().replace("-", "");
            stringRedisTemplate.opsForValue().set(
                    buildStreamTicketKey(ticket),
                    id + ":" + userId + ":" + admin,
                    STREAM_TICKET_TTL
            );
            return Result.success(new StreamTicketResponse(ticket, STREAM_TICKET_TTL.toSeconds()));
        } catch (ForbiddenException e) {
            return Result.forbidden(e.getMessage());
        } catch (Exception e) {
            return Result.error("创建实时执行票据失败: " + e.getMessage());
        }
    }

    @Operation(summary = "实时执行工作流(SSE)")
    @GetMapping(value = "/{id}/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeWorkflowStream(
            @PathVariable Long id,
            @RequestParam String inputData,
            @RequestParam String ticket
    ) {
        SseEmitter emitter = new SseEmitter(300000L);
        StreamTicketContext streamTicketContext = consumeStreamTicket(id, ticket);
        if (streamTicketContext == null) {
            sendErrorAndComplete(emitter, "执行票据无效或已过期");
            return emitter;
        }

        String emitterId = id + "_" + UUID.randomUUID();
        emitters.put(emitterId, emitter);

        Long userId = streamTicketContext.userId();
        boolean admin = streamTicketContext.admin();

        emitter.onCompletion(() -> emitters.remove(emitterId));
        emitter.onTimeout(() -> emitters.remove(emitterId));
        emitter.onError((e) -> emitters.remove(emitterId));

        Consumer<ExecutionEvent> eventCallback = event -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getEventType())
                        .data(event));
            } catch (IOException e) {
                log.error("发送 SSE 事件失败", e);
                emitters.remove(emitterId);
            }
        };

        try {
            workflowExecutionTaskExecutor.execute(() -> {
                WorkflowExecutionContextHolder.set(userId, admin);
                try {
                    Workflow workflow = workflowService.getAccessibleWorkflow(id, userId, admin);
                    WorkflowExecutor executor = engineSelector.selectEngine(workflow);
                    executor.executeWithCallback(workflow, inputData, eventCallback);
                    emitter.complete();
                } catch (Exception e) {
                    log.error("工作流执行失败", e);
                    sendErrorAndComplete(emitter, "工作流执行失败: " + e.getMessage());
                } finally {
                    WorkflowExecutionContextHolder.clear();
                }
            });
        } catch (RuntimeException e) {
            log.error("工作流执行任务提交失败", e);
            sendErrorAndComplete(emitter, "工作流执行繁忙，请稍后重试");
        }

        return emitter;
    }

    private StreamTicketContext consumeStreamTicket(Long workflowId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }

        String key = buildStreamTicketKey(ticket);
        String value = stringRedisTemplate.opsForValue().getAndDelete(key);
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.split(":", 3);
        if (parts.length != 3) {
            return null;
        }

        try {
            Long ticketWorkflowId = Long.parseLong(parts[0]);
            if (!workflowId.equals(ticketWorkflowId)) {
                return null;
            }
            return new StreamTicketContext(Long.parseLong(parts[1]), Boolean.parseBoolean(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String buildStreamTicketKey(String ticket) {
        return STREAM_TICKET_PREFIX + ticket;
    }

    private void sendErrorAndComplete(SseEmitter emitter, String message) {
        try {
            emitter.send(SseEmitter.event()
                    .name("ERROR")
                    .data(ExecutionEvent.workflowComplete("FAILED", message, 0)));
        } catch (IOException ex) {
            log.error("发送错误事件失败", ex);
        } finally {
            emitter.complete();
        }
    }

    private record StreamTicketContext(Long userId, boolean admin) {
    }

    public record StreamTicketResponse(String ticket, long expiresInSeconds) {
    }
}
