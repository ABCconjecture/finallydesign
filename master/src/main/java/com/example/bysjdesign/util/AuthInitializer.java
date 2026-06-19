package com.example.bysjdesign.util;

import com.example.bysjdesign.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class AuthInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AuthInitializer.class);

    private final AuthService authService;

    public AuthInitializer(AuthService authService) {
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        authService.ensureBootstrapUser();
        logger.info("系统登录账号初始化完成");
    }
}