package com.team7.mobile.api.controller;

import org.springframework.web.bind.annotation.*;

/**
 * 行程管理 API 控制器
 * <p>
 * 接口：
 * - POST   /api/trips                — 创建新行程（调用 Agent 生成计划）
 * - GET    /api/trips                 — 获取当前用户的所有行程
 * - GET    /api/trips/{id}            — 获取行程详情（含每日 itinerary）
 * - PUT    /api/trips/{id}            — 手动修改行程
 * - DELETE /api/trips/{id}            — 取消行程
 * - POST   /api/trips/{id}/agent-chat — 与 Agent 对话修改行程
 */
// TODO: 定义 REST 端点，调用 TripService，支持分页查询
@RestController
@RequestMapping("/api/trips")
public class TripController {
}
