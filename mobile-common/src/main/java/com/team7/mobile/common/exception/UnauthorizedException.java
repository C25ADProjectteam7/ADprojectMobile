package com.team7.mobile.common.exception;

/**
 * 未授权异常 — 用户未登录或 Token 无效时抛出（HTTP 401）
 * <p>
 * 由 JWT 过滤器检测到无效/过期 Token 时触发
 */
// TODO: 继承 BusinessException，errorCode = "UNAUTHORIZED"
