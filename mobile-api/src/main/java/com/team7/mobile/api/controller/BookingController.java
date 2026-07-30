package com.team7.mobile.api.controller;

import org.springframework.web.bind.annotation.*;

/**
 * 预订管理 API 控制器
 * <p>
 * 接口：
 * - POST /api/bookings/flight     — 预订航班
 * - POST /api/bookings/hotel      — 预订酒店
 * - GET  /api/bookings            — 获取当前用户的预订列表
 * - GET  /api/bookings/{id}       — 获取预订详情
 * - PUT  /api/bookings/{id}/cancel — 取消预订
 */
// TODO: 定义预订 REST 端点，所有操作需认证
@RestController
@RequestMapping("/api/bookings")
public class BookingController {
}
