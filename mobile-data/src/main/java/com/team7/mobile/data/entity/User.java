package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户实体 — 映射 database:mobile 的 users 表
 * <p>
 * 存储员工账号信息，密码使用 BCrypt 加密存储
 * role 字段控制 RBAC 权限（EMPLOYEE/MANAGER/FINANCE/ADMIN）
 */
// TODO: 定义字段 — id, username, password(BCrypt), email, department, phone, role, avatarUrl, createdAt, updatedAt
@Entity
@Table(name = "users")
public class User {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
}
