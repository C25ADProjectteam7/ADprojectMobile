package com.team7.mobile.business.service;

import org.springframework.stereotype.Service;

/**
 * 预订服务 — 航班和酒店的预订、查询、取消
 * <p>
 * Agent 规划完毕后调用此服务执行实际预订：
 * 1. 根据 Agent 选择的最优方案，调用 Amadeus 模拟预订 API
 * 2. 预订成功 → 创建 Booking 记录 + 更新 ItineraryItem 状态
 * 3. 预订失败 → 通知 Agent 尝试备选方案
 * <p>
 * 也支持用户手动选择并预订
 */
// TODO: 实现 bookFlight(), bookHotel(), cancelBooking(), getBookingByTripId()
@Service
public class BookingService {
}
