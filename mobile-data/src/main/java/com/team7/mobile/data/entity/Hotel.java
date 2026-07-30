package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 酒店数据实体 — 映射 hotels 表
 * <p>
 * 缓存从 Amadeus API 拉取的酒店信息
 * amenities 字段使用 JSON 格式存储设施列表（WiFi/泳池/健身房等）
 * rating 字段存储评分（1-5 或 1-10 根据 API 返回）
 */
// TODO: 定义字段 — id, name, city, address, latitude, longitude
// TODO: pricePerNight, currency, rating, amenities(JSON), description
// TODO: source("AMADEUS"/"MOCK"), cachedAt
@Entity
@Table(name = "hotels")
public class Hotel {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

}
