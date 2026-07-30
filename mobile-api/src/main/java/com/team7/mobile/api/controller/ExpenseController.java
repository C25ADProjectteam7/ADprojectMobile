package com.team7.mobile.api.controller;

import org.springframework.web.bind.annotation.*;

/**
 * 费用报销 API 控制器
 * <p>
 * 接口：
 * - POST   /api/expenses                — 提交报销申请
 * - POST   /api/expenses/upload-receipt  — 上传发票图片（multipart/form-data）→ OCR 识别
 * - GET    /api/expenses                 — 获取当前用户的报销列表
 * - GET    /api/expenses/{id}            — 获取报销详情
 * <p>
 * 财务审核接口（Web 端调用）：
 * - PUT    /api/expenses/{id}/approve   — 审批通过
 * - PUT    /api/expenses/{id}/reject    — 驳回
 */
// TODO: 定义报销 REST 端点，支持文件上传
@RestController
@RequestMapping("/api/expenses")
public class ExpenseController {
}
