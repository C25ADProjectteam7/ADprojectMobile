package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 航班数据实体 — 映射 flights 表
 * <p>
 * 缓存从 Amadeus API 拉取的航班信息，避免重复调用外部 API
 * 数据定期刷新（source 字段标记来源，cached_at 记录缓存时间）
 * 航班搜索时优先从本地缓存读取，过期后重新拉取
 */
// TODO: 定义字段 — id, flightNumber, airline, departureAirport, arrivalAirport
// TODO: departureTime, arrivalTime, price, currency, cabinClass, availableSeats
// TODO: source("AMADEUS"/"MOCK"), cachedAt
@Entity
@Table(name = "flights")
public class Flight {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

}
