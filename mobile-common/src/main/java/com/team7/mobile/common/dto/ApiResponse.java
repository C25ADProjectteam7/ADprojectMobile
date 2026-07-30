package com.team7.mobile.common.dto;

/**
 * 统一 API 响应包装类 — 所有 REST API 返回格式统一为此结构
 * <p>
 * 包含三个字段：code (状态码), message (提示信息), data (业务数据)
 * 泛型 T 支持任意类型的数据载荷
 * <p>
 * 示例：ApiResponse<UserDTO> response = ApiResponse.success(userDTO);
 */
// TODO: 定义 code, message, data 字段；提供 success() 和 error() 静态工厂方法

