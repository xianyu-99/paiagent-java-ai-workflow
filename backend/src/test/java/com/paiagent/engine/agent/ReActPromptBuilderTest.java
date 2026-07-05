package com.paiagent.engine.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReActPromptBuilderTest {

    @Test
    void shouldPutMemoryContextOnlyInSystemPrompt() {
        String memory = "Relevant VPN memory";

        String systemPrompt = ReActPromptBuilder.buildSystemPrompt(List.of(), "custom", memory);
        String userPrompt = ReActPromptBuilder.buildUserPrompt("VPN issue", "", memory);

        assertThat(systemPrompt).contains(memory);
        assertThat(userPrompt).doesNotContain(memory);
        assertThat(userPrompt).contains("Task: VPN issue");
    }
}
