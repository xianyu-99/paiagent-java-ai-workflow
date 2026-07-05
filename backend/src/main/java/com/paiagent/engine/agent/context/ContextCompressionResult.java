package com.paiagent.engine.agent.context;

public record ContextCompressionResult(
        String content,
        int originalLength,
        int compressedLength,
        int originalLineCount,
        int keptLineCount,
        int droppedLineCount,
        double compressionRatio,
        boolean compressed
) {
}
