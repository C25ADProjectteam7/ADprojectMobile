package com.team7.mobile.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Mobile Platform — Spring Boot entry point.
 * Scans all submodules (common, security, data, business) for beans.
 */
@SpringBootApplication(scanBasePackages = "com.team7.mobile")
@EnableJpaRepositories("com.team7.mobile.data.repository")
@EntityScan("com.team7.mobile.data.entity")
public class MobileApplication {
    public static void main(String[] args) {
        SpringApplication.run(MobileApplication.class, args);
    }
}
