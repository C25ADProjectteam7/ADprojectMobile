package com.team7.mobile.security.service;

import org.springframework.stereotype.Service;

/**
 * Spring Security UserDetailsService 实现 — 从数据库加载用户信息
 * <p>
 * Spring Security 认证流程的核心组件：
 * 1. 根据 username 查询 User 实体
 * 2. 转换为 Spring Security 的 UserDetails 对象（包含用户名、密码、权限集合）
 * 3. 用户不存在时抛出 UsernameNotFoundException
 * 4. 密码比对由 AuthenticationManager 自动完成（BCrypt 加密匹配）
 */
// TODO: 实现 loadUserByUsername() → 查询 UserRepository → 返回 UserDetails
@Service
public class UserDetailsServiceImpl {
}
