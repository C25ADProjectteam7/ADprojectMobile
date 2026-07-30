package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预订记录实体 — 映射 bookings 表
 * <p>
 * Agent 自动调用 Amadeus 模拟预订 API 后生成的预订记录
 * type: FLIGHT/HOTEL
 * bookingRef: 外部预订系统的确认编号
 * status: PENDING → CONFIRMED → CHECKED_IN → COMPLETED
 */
// TODO: 定义字段 — id, trip(FK), user(FK), type, flight(FK nullable), hotel(FK nullable)
// TODO: bookingRef(外部预订号), price, currency, status, bookedAt, createdAt
public class Booking {
}
