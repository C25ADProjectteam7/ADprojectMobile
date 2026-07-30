package com.team7.mobile.security.filter;

import org.springframework.stereotype.Component;

/**
 * JWT 认证过滤器 — 在每次 HTTP 请求到达 Controller 之前拦截并验证 Token
 * <p>
 * 流程：
 * 1. 从 Authorization Header 中提取 "Bearer <token>"
 * 2. 调用 JwtTokenProvider.validateToken() 验证
 * 3. 验证通过则从 Token 提取用户信息，创建 Authentication 对象并写入 SecurityContext
 * 4. 验证失败则直接返回 401，不继续走过滤器链
 * <p>
 * 继承 OncePerRequestFilter：保证每个请求只经过一次过滤
 */
// TODO: 实现 doFilterInternal()，从 Header 提取 Token → 验证 → 设置 SecurityContext
@Component
public class JwtAuthFilter {
}
