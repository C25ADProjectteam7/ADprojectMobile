package com.team7.mobile.api.advice;

import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器 — 统一处理所有 Controller 抛出的异常
 * <p>
 * 实现 @RestControllerAdvice + @ExceptionHandler，将异常转换为统一的 ApiResponse 格式
 * <p>
 * 异常映射：
 * - ResourceNotFoundException → 404 + ApiResponse.error(404, "资源不存在")
 * - UnauthorizedException → 401 + ApiResponse.error(401, "未授权")
 * - ForbiddenException → 403 + ApiResponse.error(403, "禁止访问")
 * - MethodArgumentNotValidException → 400 + ApiResponse.error(400, "参数校验失败")
 * - ExternalApiException → 502 + ApiResponse.error(502, "外部服务异常")
 * - Throwable → 500 + ApiResponse.error(500, "服务器内部错误，请稍后重试")
 * <p>
 * 注意：500 错误不应泄露内部异常细节（包含堆栈信息）到前端响应
 * 生产环境中使用日志框架记录完整错误，只返回用户友好的错误描述
 */
// TODO: 定义所有 @ExceptionHandler，统一返回 ApiResponse，区分开发/生产环境的错误详情
@RestControllerAdvice
public class GlobalExceptionHandler {
}
