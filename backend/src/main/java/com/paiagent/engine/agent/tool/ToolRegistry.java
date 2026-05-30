package com.paiagent.engine.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 工具注册中心
 * 自动扫描并注册所有带@ToolAnnotation注解的Spring Bean
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    @Autowired
    public ToolRegistry(List<Tool> toolBeans) {
        if (toolBeans == null || toolBeans.isEmpty()) {
            log.info("No Tool implementations found");
            return;
        }

        log.info("Registering {} tool(s)", toolBeans.size());
        for (Tool tool : toolBeans) {
            register(tool);
        }
    }

    /**
     * 注册一个工具
     */
    private void register(Tool tool) {
        String name = resolveName(tool);
        String description = resolveDescription(tool);

        if (tools.containsKey(name)) {
            throw new IllegalArgumentException(
                    "Duplicate tool name detected: '" + name + "'. " +
                    "Tool names must be unique within the registry.");
        }

        tools.put(name, tool);
        log.info("Registered tool: {} - {}", name, description);
    }

    /**
     * 解析工具名称
     * 优先使用@ToolAnnotation注解中的name，否则从类名推导
     */
    private String resolveName(Tool tool) {
        ToolAnnotation annotation = tool.getClass().getAnnotation(ToolAnnotation.class);
        if (annotation != null && !annotation.name().isBlank()) {
            return annotation.name();
        }
        return deriveNameFromClass(tool.getClass());
    }

    /**
     * 从类名推导工具名称
     * 小写首字母，移除"Tool"后缀
     */
    private String deriveNameFromClass(Class<?> clazz) {
        String className = clazz.getSimpleName();
        // 移除 "Tool" 后缀
        if (className.endsWith("Tool")) {
            className = className.substring(0, className.length() - 4);
        }
        // 小写首字母
        if (className.isEmpty()) {
            return clazz.getSimpleName().toLowerCase();
        }
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    /**
     * 解析工具描述
     * 优先使用@ToolAnnotation注解中的description，否则使用getDescription()
     */
    private String resolveDescription(Tool tool) {
        ToolAnnotation annotation = tool.getClass().getAnnotation(ToolAnnotation.class);
        if (annotation != null && !annotation.description().isBlank()) {
            return annotation.description();
        }
        return tool.getDescription();
    }

    /**
     * 根据名称获取工具
     */
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * 获取所有已注册的工具
     */
    public List<Tool> getAllTools() {
        return Collections.unmodifiableList(List.copyOf(tools.values()));
    }

    /**
     * 检查工具是否存在
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * 根据名称列表获取工具
     */
    public List<Tool> getToolsByNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        return names.stream()
                .map(tools::get)
                .filter(tool -> tool != null)
                .collect(Collectors.toList());
    }
}
