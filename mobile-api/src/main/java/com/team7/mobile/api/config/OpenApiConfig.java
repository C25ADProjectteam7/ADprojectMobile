package com.team7.mobile.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Swagger config.
 * Access: http://localhost:8080/swagger-ui.html
 * Adds a JWT Bearer security scheme so protected endpoints can be tested in the UI.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI team7OpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Team7 Mobile API")
                        .description("Smart Travel & Expense Hub — Mobile backend REST API")
                        .version("1.0.0"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
