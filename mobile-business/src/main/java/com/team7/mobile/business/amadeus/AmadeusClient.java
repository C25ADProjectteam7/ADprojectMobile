package com.team7.mobile.business.amadeus;

import org.springframework.stereotype.Service;

/**
 * Amadeus API 客户端 — 航班搜索、酒店搜索、价格查询
 * <p>
 * 使用 Amadeus Self-Service API（免费额度 2000 次/月）
 * <p>
 * 核心方法：
 * - searchFlights(origin, destination, date, passengers): 搜索航班
 * - searchHotels(cityCode, checkIn, checkOut, adults): 搜索酒店
 * - getFlightPrice(flightOfferId): 获取航班实时价格
 * - simulateBooking(offerId, passengerInfo): 模拟预订（测试环境用）
 * <p>
 * 认证方式：OAuth2 Client Credentials → 获取 Access Token → 调用 API
 * 需在 Amadeus 开发者门户注册获取 API Key + Secret
 * <p>
 * 缓存策略：搜索结果写入 flights/hotels 表，TTL 后自动刷新
 */
// TODO: 实现 OAuth2 认证流程，封装 REST 调用，结果缓存逻辑，限流处理
@Service
public class AmadeusClient {
}
