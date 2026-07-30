package com.team7.mobile.api.controller;

import org.springframework.web.bind.annotation.*;

/**
 * 认证 API 控制器 — 用户登录、注册、Token 刷新
 * <p>
 * 路径前缀：/api/auth
 * 接口：
 * - POST /api/auth/register   — 用户注册（公开）
 * - POST /api/auth/login      — 用户登录，返回 JWT Token（公开）
 * - POST /api/auth/refresh    — Token 过期续期（需认证）
 * - POST /api/auth/logout     — 退出登录（需认证，可选：Token 黑名单）
 * <p>
 * 登录成功后，前端将 Token 存储在 localStorage/SharedPreferences
 * 后续所有请求在 Header 中携带：Authorization: Bearer <token>
 */
// TODO: 定义 REST 端点，调用 AuthService，返回 ApiResponse<LoginResponse>
@RestController
@RequestMapping("/api/auth")
public class AuthController {
}
