package com.paiagent.engine.execution;

/**
 * 当前工作流执行请求的用户上下文。
 *
 * <p>节点实际运行在受控线程池中，因此执行引擎会把这里的上下文复制到节点输入的内部字段里。
 */
public final class WorkflowExecutionContextHolder {

    private static final ThreadLocal<WorkflowExecutionContext> CONTEXT = new ThreadLocal<>();

    private WorkflowExecutionContextHolder() {
    }

    public static void set(Long userId, boolean admin) {
        CONTEXT.set(new WorkflowExecutionContext(userId, admin));
    }

    public static WorkflowExecutionContext get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public record WorkflowExecutionContext(Long userId, boolean admin) {
    }
}
