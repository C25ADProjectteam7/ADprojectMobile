package com.team7.mobile.security.config;

import org.springframework.context.annotation.Configuration;

/**
 * Spring Security 核心配置 — 定义安全规则和过滤器链
 * <p>
 * 职责：
 * 1. 配置哪些 API 路径需要认证，哪些公开（登录/注册/API 文档）
 * 2. 注入 JwtAuthFilter 到过滤器链（在 UsernamePasswordAuthenticationFilter 之前）
 * 3. 配置 CORS 跨域策略
 * 4. 配置 BCryptPasswordEncoder Bean
 * 5. 禁用 CSRF（前后端分离 + JWT 场景不需要）
 * 6. 配置 Session 管理为 STATELESS（JWT 无状态认证）
 * <p>
 * 公开路径：/api/auth/**, /swagger-ui/**, /v3/api-docs/**, /actuator/health
 * 其余路径：需要有效 JWT Token
 */
// TODO: 实现 SecurityFilterChain Bean，配置 CORS、CSRF 禁用、Session STATELESS、路径权限
@Configuration
public class SecurityConfig {
}
