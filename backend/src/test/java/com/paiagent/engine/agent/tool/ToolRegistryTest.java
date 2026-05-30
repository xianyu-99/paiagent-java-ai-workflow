package com.paiagent.engine.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    static class SearchTool implements Tool {
        @Override
        public String getName() { return "search"; }
        @Override
        public String getDescription() { return "Search the web"; }
        @Override
        public String execute(Map<String, Object> args) { return "done"; }
        @Override
        public Map<String, Object> getParameterSchema() { return Map.of(); }
    }

    static class CalculatorTool implements Tool {
        @Override
        public String getName() { return "calculator"; }
        @Override
        public String getDescription() { return "Do math"; }
        @Override
        public String execute(Map<String, Object> args) { return "done"; }
        @Override
        public Map<String, Object> getParameterSchema() { return Map.of(); }
    }

    static class WeatherTool implements Tool {
        @Override
        public String getName() { return "weather"; }
        @Override
        public String getDescription() { return "Get weather"; }
        @Override
        public String execute(Map<String, Object> args) { return "done"; }
        @Override
        public Map<String, Object> getParameterSchema() { return Map.of(); }
    }

    @ToolAnnotation(name = "customName", description = "Custom desc")
    static class AnnotatedTool implements Tool {
        @Override
        public String getName() { return "derivedName"; }
        @Override
        public String getDescription() { return "derived desc"; }
        @Override
        public String execute(Map<String, Object> args) { return "done"; }
        @Override
        public Map<String, Object> getParameterSchema() { return Map.of(); }
    }

    @Test
    void shouldCreateEmptyRegistry() {
        ToolRegistry registry = new ToolRegistry(Collections.emptyList());

        assertTrue(registry.getAllTools().isEmpty());
        assertFalse(registry.hasTool("any"));
        assertEquals(Optional.empty(), registry.getTool("any"));
    }

    @Test
    void shouldRegisterSingleTool() {
        Tool tool = new SearchTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertEquals(1, registry.getAllTools().size());
        assertTrue(registry.hasTool("search"));
        assertEquals(Optional.of(tool), registry.getTool("search"));
    }

    @Test
    void shouldRegisterMultipleTools() {
        Tool search = new SearchTool();
        Tool calc = new CalculatorTool();
        ToolRegistry registry = new ToolRegistry(List.of(search, calc));

        assertEquals(2, registry.getAllTools().size());
        assertTrue(registry.hasTool("search"));
        assertTrue(registry.hasTool("calculator"));
        assertFalse(registry.hasTool("unknown"));
    }

    @Test
    void shouldThrowOnDuplicateToolNames() {
        Tool tool1 = new SearchTool();
        Tool tool2 = new SearchTool();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new ToolRegistry(List.of(tool1, tool2)));
        assertTrue(exception.getMessage().contains("Duplicate tool name detected"));
        assertTrue(exception.getMessage().contains("search"));
    }

    @Test
    void shouldGetToolByName() {
        Tool search = new SearchTool();
        Tool calc = new CalculatorTool();
        ToolRegistry registry = new ToolRegistry(List.of(search, calc));

        assertEquals(Optional.of(search), registry.getTool("search"));
        assertEquals(Optional.of(calc), registry.getTool("calculator"));
    }

    @Test
    void shouldReturnEmptyForUnknownTool() {
        ToolRegistry registry = new ToolRegistry(List.of(new SearchTool()));

        assertEquals(Optional.empty(), registry.getTool("unknown"));
    }

    @Test
    void shouldGetToolsByNames() {
        Tool search = new SearchTool();
        Tool calc = new CalculatorTool();
        Tool weather = new WeatherTool();
        ToolRegistry registry = new ToolRegistry(List.of(search, calc, weather));

        List<Tool> result = registry.getToolsByNames(List.of("search", "weather"));

        assertEquals(2, result.size());
        assertTrue(result.contains(search));
        assertTrue(result.contains(weather));
        assertFalse(result.contains(calc));
    }

    @Test
    void shouldUseAnnotationNameOverDerivedName() {
        AnnotatedTool tool = new AnnotatedTool();
        ToolRegistry registry = new ToolRegistry(List.of(tool));

        assertTrue(registry.hasTool("customName"));
        assertFalse(registry.hasTool("derivedName"));
        assertEquals(Optional.of(tool), registry.getTool("customName"));
    }
}
