package com.team7.mobile.business.google;

import org.springframework.stereotype.Service;

/**
 * Google Places API 客户端 — 餐厅搜索、景点搜索、图片下载
 * <p>
 * 使用 Google Places API（新用户 $200/月免费额度）
 * <p>
 * 核心方法：
 * - searchRestaurants(city, cuisine, priceLevel): 搜索餐厅
 * - searchAttractions(city, category): 搜索景点
 * - getPlaceDetails(placeId): 获取地点详情
 * - downloadPlacePhotos(placeId, maxCount): 下载地点照片到本地服务器
 * <p>
 * 图片下载策略：
 * - Agent 确定行程目的地 → 自动搜索当地餐厅/景点 → 下载图片到本地
 * - 图片存储在 /var/www/adproject-mobile/images/places/
 * - 下载记录写入 place_images 表
 */
// TODO: 实现 Places API 调用，图片批量下载，结果缓存，API Key 管理
@Service
public class GooglePlacesClient {
}
