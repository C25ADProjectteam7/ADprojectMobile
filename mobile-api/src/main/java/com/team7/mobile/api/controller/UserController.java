package com.team7.mobile.api.controller;

import org.springframework.web.bind.annotation.*;

/**
 * 用户信息 API 控制器
 * <p>
 * 接口：
 * - GET    /api/users/me         — 获取当前登录用户信息
 * - PUT    /api/users/me         — 更新个人信息（手机号、部门等）
 * - PUT    /api/users/me/password — 修改密码
 * - POST   /api/users/me/avatar  — 上传头像
 */
// TODO: 定义用户信息 REST 端点，仅本人可操作
@RestController
@RequestMapping("/api/users")
public class UserController {
}
