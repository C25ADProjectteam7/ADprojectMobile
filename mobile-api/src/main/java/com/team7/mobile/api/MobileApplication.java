package com.team7.mobile.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Mobile 平台 Spring Boot 主入口 — 整个应用的启动类
 * <p>
 * @SpringBootApplication 包含三个注解：
 * - @Configuration: 声明配置类
 * - @EnableAutoConfiguration: 自动配置
 * - @ComponentScan: 组件扫描
 * <p>
 * scanBasePackages 覆盖所有子模块的包路径，确保每个模块的 Bean 都被扫描到
 * 启动命令：java -jar adproject-mobile-api.jar 或 ./gradlew :mobile-api:bootRun
 */
@SpringBootApplication(scanBasePackages = "com.team7.mobile")
public class MobileApplication {
    public static void main(String[] args) {
        SpringApplication.run(MobileApplication.class, args);
    }
}
