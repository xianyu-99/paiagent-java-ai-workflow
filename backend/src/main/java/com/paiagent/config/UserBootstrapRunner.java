package com.paiagent.config;

import com.paiagent.service.AuthService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class UserBootstrapRunner implements ApplicationRunner {

    private final AuthService authService;

    public UserBootstrapRunner(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        authService.ensureDefaultAdmin();
    }
}
