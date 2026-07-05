package com.paiagent.engine.agent.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextCompressorTest {

    private final ContextCompressor compressor = new ContextCompressor();

    @Test
    void shouldKeepShortContextUnchanged() {
        ContextCompressionResult result = compressor.compress("short context", "context", 2000);

        assertThat(result.content()).isEqualTo("short context");
        assertThat(result.compressed()).isFalse();
        assertThat(result.droppedLineCount()).isZero();
    }

    @Test
    void shouldKeepHighSignalLinesWhenContextIsLong() {
        StringBuilder context = new StringBuilder();
        context.append("=== Knowledge Context ===\n");
        for (int i = 0; i < 80; i++) {
            context.append("ordinary filler line ").append(i).append(" without important content\n");
        }
        context.append("[GraphEvidence] VPN --[handled_by]--> IT\n");
        context.append("Final Answer: VPN certificate issues should contact IT.\n");

        ContextCompressionResult result = compressor.compress(
                context.toString(),
                "VPN certificate should contact who",
                420
        );

        assertThat(result.compressed()).isTrue();
        assertThat(result.content()).contains("GraphEvidence");
        assertThat(result.content()).contains("VPN");
        assertThat(result.content()).contains("ContextCompression");
        assertThat(result.droppedLineCount()).isGreaterThan(0);
        assertThat(result.compressionRatio()).isLessThan(1.0);
    }

    @Test
    void shouldRespectCharacterBudget() {
        String context = "line\n".repeat(200) + "[Knowledge 1] reimbursement approval by finance\n";

        ContextCompressionResult result = compressor.compress(context, "reimbursement finance", 300);

        assertThat(result.content().length()).isLessThanOrEqualTo(300);
    }
}
