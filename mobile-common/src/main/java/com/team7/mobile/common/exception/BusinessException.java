package com.team7.mobile.common.exception;

/**
 * 业务异常基类 — 所有自定义业务异常继承此类
 * <p>
 * 携带 errorCode (业务错误码，如 "TRIP_NOT_FOUND") 和 message (用户可读的错误描述)
 * Spring 全局异常处理器根据异常类型返回对应的 HTTP 状态码
 */
// TODO: 定义 errorCode + message，子类扩展具体异常类型

