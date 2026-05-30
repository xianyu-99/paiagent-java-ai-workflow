package com.paiagent.engine.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentStateTest {

    @Test
    void shouldInitializeCorrectly() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);

        assertEquals("session-1", state.getSessionId());
        assertEquals("What is 2+2?", state.getTask());
        assertEquals(5, state.getMaxIterations());
        assertEquals(0, state.getCurrentIteration());
        assertFalse(state.isFinished());
        assertNull(state.getFinalAnswer());
        assertNull(state.getErrorMessage());
        assertNull(state.getCurrentStep());
        assertTrue(state.getSteps().isEmpty());
    }

    @Test
    void shouldAddThought() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);
        state.addThought(1, "I need to calculate this");

        assertEquals(1, state.getCurrentIteration());
        assertNotNull(state.getCurrentStep());
        assertEquals(1, state.getCurrentStep().getIteration());
        assertEquals("I need to calculate this", state.getCurrentStep().getThought());
        assertNull(state.getCurrentStep().getAction());
        assertNull(state.getCurrentStep().getObservation());
    }

    @Test
    void shouldAddActionToCurrentStep() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);
        state.addThought(1, "I need to calculate this");
        state.addAction("calculator", "{\"expr\": \"2+2\"}");

        AgentState.Step current = state.getCurrentStep();
        assertEquals("calculator", current.getAction());
        assertEquals("{\"expr\": \"2+2\"}", current.getActionInput());
        assertEquals("I need to calculate this", current.getThought());
    }

    @Test
    void shouldAddObservationToCurrentStep() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);
        state.addThought(1, "I need to calculate this");
        state.addAction("calculator", "{\"expr\": \"2+2\"}");
        state.addObservation("4");

        AgentState.Step current = state.getCurrentStep();
        assertEquals("4", current.getObservation());
        assertEquals("calculator", current.getAction());
    }

    @Test
    void shouldCompleteReActCycle() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);

        state.addThought(1, "I need to calculate this");
        state.addAction("calculator", "{\"expr\": \"2+2\"}");
        state.addObservation("4");

        state.addThought(2, "Now I have the answer");
        state.addAction("finish", "{}");
        state.addObservation("Done");

        assertEquals(2, state.getSteps().size());
        assertEquals(2, state.getCurrentIteration());
    }

    @Test
    void shouldFinishWithFinalAnswer() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);
        state.finish("The answer is 4");

        assertTrue(state.isFinished());
        assertEquals("The answer is 4", state.getFinalAnswer());
        assertNull(state.getErrorMessage());
    }

    @Test
    void shouldFailWithError() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);
        state.fail("Something went wrong");

        assertTrue(state.isFinished());
        assertNull(state.getFinalAnswer());
        assertEquals("Something went wrong", state.getErrorMessage());
    }

    @Test
    void shouldDetectMaxIterations() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 3);

        assertFalse(state.hasReachedMaxIterations());

        state.addThought(1, "First thought");
        assertFalse(state.hasReachedMaxIterations());

        state.addThought(2, "Second thought");
        assertFalse(state.hasReachedMaxIterations());

        state.addThought(3, "Third thought");
        assertTrue(state.hasReachedMaxIterations());
    }

    @Test
    void shouldFormatHistoryAsText() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);

        state.addThought(1, "I need to calculate this");
        state.addAction("calculator", "{\"expr\": \"2+2\"}");
        state.addObservation("4");

        state.addThought(2, "Now I have the answer");
        state.addObservation("Confirmed");

        String history = state.getHistoryAsText();

        assertTrue(history.contains("Thought: I need to calculate this"));
        assertTrue(history.contains("Action: calculator"));
        assertTrue(history.contains("Action Input: {\"expr\": \"2+2\"}"));
        assertTrue(history.contains("Observation: 4"));
        assertTrue(history.contains("Thought: Now I have the answer"));
        assertTrue(history.contains("Observation: Confirmed"));
    }

    @Test
    void shouldIgnoreIncompleteStepsInHistory() {
        AgentState state = new AgentState("session-1", "What is 2+2?", 5);

        state.addThought(1, "I need to calculate this");
        state.addAction("calculator", "{\"expr\": \"2+2\"}");
        state.addObservation("4");

        state.addThought(2, "Current thought without observation");

        String history = state.getHistoryAsText();

        assertTrue(history.contains("Thought: I need to calculate this"));
        assertFalse(history.contains("Current thought without observation"));
    }
}
