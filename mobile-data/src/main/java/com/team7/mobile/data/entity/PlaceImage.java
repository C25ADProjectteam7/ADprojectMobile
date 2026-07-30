package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 地点图片实体 — 映射 place_images 表
 * <p>
 * Agent 从 Google Places API 搜索结果中自动下载图片并存储到服务器本地
 * placeType 标识关联类型：RESTAURANT / ATTRACTION / HOTEL
 * imageUrl 是原始 URL，localPath 是服务器本地存储路径
 * 图片统一存储在 /var/www/adproject-mobile/images/ 目录
 */
// TODO: 定义字段 — id, placeType, placeId, imageUrl, localPath, downloadedAt
public class PlaceImage {
}
