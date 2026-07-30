package com.team7.mobile.data.config;

import org.springframework.context.annotation.Configuration;

/**
 * JPA 审计配置 — 自动填充 createdAt/updatedAt 等审计字段
 * <p>
 * 启用 @EnableJpaAuditing，配合 @CreatedDate/@LastModifiedDate 注解
 * 所有 Entity 无需手动设置时间戳
 */
// TODO: 添加 @EnableJpaAuditing 注解，配置 AuditorAware<Long> Bean
@Configuration
public class JpaAuditConfig {
}
