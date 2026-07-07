package com.paiagent.engine.llm.prompt;

public record PromptCacheResult(
        String content,
        boolean cacheable,
        boolean hit,
        String key,
        int contentChars,
        int estimatedSavedChars
) {
    public static PromptCacheResult uncached(String content) {
        String safeContent = content == null ? "" : content;
        return new PromptCacheResult(safeContent, false, false, null, safeContent.length(), 0);
    }
}
