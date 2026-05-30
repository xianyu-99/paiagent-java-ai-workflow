package com.paiagent.common;

import com.paiagent.engine.validation.WorkflowValidationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void shouldReturnHttp400ForValidationFailure() throws Exception {
        mockMvc.perform(post("/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message", containsString("name")));
    }

    @Test
    void shouldReturnHttp400ForMalformedJson() throws Exception {
        mockMvc.perform(post("/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void shouldReturnHttp405ForUnsupportedMethod() throws Exception {
        mockMvc.perform(put("/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\"}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(405));
    }

    @Test
    void shouldReturnHttp403ForForbiddenException() throws Exception {
        mockMvc.perform(post("/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void shouldReturnHttp400ForWorkflowValidationException() throws Exception {
        mockMvc.perform(post("/workflow-validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @RestController
    private static class TestController {

        @PostMapping("/validate")
        Result<Void> validate(@Valid @RequestBody TestRequest request) {
            return Result.success();
        }

        @PostMapping("/forbidden")
        Result<Void> forbidden() {
            throw new ForbiddenException("no access");
        }

        @PostMapping("/workflow-validation")
        Result<Void> workflowValidation() {
            throw new WorkflowValidationException("invalid workflow");
        }
    }

    private record TestRequest(@NotBlank String name) {
    }
}
