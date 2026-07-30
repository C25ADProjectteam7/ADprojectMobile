package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 景点数据实体 — 映射 attractions 表
 * <p>
 * 缓存从 Google Places API 拉取的景点/地标信息
 * category: MUSEUM/PARK/HISTORICAL/ENTERTAINMENT/SHOPPING/NATURE 等
 */
// TODO: 定义字段 — id, name, city, address, latitude, longitude
// TODO: category, rating, description, photos(JSON), openingHours, ticketPrice
// TODO: source("GOOGLE_PLACES"), cachedAt
public class Attraction {
}
