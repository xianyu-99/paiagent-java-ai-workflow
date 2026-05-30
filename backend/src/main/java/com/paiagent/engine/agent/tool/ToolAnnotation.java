package com.paiagent.engine.agent.tool;

import org.springframework.stereotype.Component;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 工具注解
 * 标注在Tool接口的实现类上，使其自动成为Spring Bean并被ToolRegistry注册
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface ToolAnnotation {

    /**
     * 工具名称，默认使用类名的小写驼峰形式
     */
    String name() default "";

    /**
     * 工具描述
     */
    String description() default "";
}
