package com.team7.mobile.business.service;

import org.springframework.stereotype.Service;

/**
 * 认证服务 — 处理用户登录、注册、密码重置等认证相关业务
 * <p>
 * 登录流程：
 * 1. 接收 LoginRequest → 通过 AuthenticationManager 验证用户名密码
 * 2. 验证通过 → 调用 JwtTokenProvider 生成 JWT Token
 * 3. 返回 LoginResponse（包含 accessToken + 用户基本信息）
 * <p>
 * 注册流程：
 * 1. 校验用户名/邮箱是否已存在
 * 2. BCrypt 加密密码 → 保存 User 到数据库
 * 3. 返回注册成功信息
 */
// TODO: 实现 login(), register(), refreshToken(), changePassword()
@Service
public class AuthService {
}
