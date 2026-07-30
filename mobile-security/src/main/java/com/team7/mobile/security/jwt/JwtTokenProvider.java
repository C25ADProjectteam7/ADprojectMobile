package com.team7.mobile.security.jwt;

import org.springframework.stereotype.Component;

/**
 * JWT Token 工具类 — 负责 Token 的创建、解析、验证
 * <p>
 * 使用 HMAC-SHA256 签名算法（secretKey 从配置文件读取，生产环境通过环境变量注入）
 * <p>
 * 核心方法：
 * - generateToken(username, role): 生成 Access Token（有效期 24h）
 * - validateToken(token): 验证签名和有效期
 * - getUsernameFromToken(token): 从 Token 中提取用户名
 * - getRoleFromToken(token): 从 Token 中提取角色
 * <p>
 * Token Claims: sub(用户名), role(角色), iat(签发时间), exp(过期时间)
 */
// TODO: 实现 generateToken(), validateToken(), parseToken(), 使用 JJWT 库
@Component
public class JwtTokenProvider {
}
