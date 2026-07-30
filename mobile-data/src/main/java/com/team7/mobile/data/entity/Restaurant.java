package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 餐厅数据实体 — 映射 restaurants 表
 * <p>
 * 缓存从 Google Places API 拉取的餐厅信息
 * photos 字段存储图片 URL 列表（JSON 数组），Agent 自动下载到 place_images 表
 * priceLevel: 1($) ~ 4($$$$)
 */
// TODO: 定义字段 — id, name, city, address, latitude, longitude
// TODO: cuisineType, priceLevel, rating, photos(JSON), openingHours
// TODO: source("GOOGLE_PLACES"), cachedAt
@Entity
@Table(name = "restaurants")
public class Restaurant {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

}
