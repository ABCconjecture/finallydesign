package com.example.bysjdesign;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication // 自动扫描 com.example.bysjdesign 下的所有子包
@EnableScheduling
public class BysjDesignApplication {
    public static void main(String[] args) {
        SpringApplication.run(BysjDesignApplication.class, args);
        System.out.println("========================================");
        System.out.println("校园网用户健康行为分析与画像系统已启动");
        System.out.println("后端服务运行于: http://localhost:8080");
        System.out.println("========================================");
    }
}