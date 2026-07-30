package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 行程单项实体 — 映射 itinerary_items 表
 * <p>
 * 一条具体的行程安排：航班、酒店入住、餐厅、景点、会议等
 * type 字段区分项目类型：FLIGHT/HOTEL/RESTAURANT/ATTRACTION/MEETING/TRANSPORT
 */
// TODO: 定义字段 — id, itinerary(FK), type, startTime, endTime, title, description, location
// TODO: bookingRef(关联预订号), price, currency, status
public class ItineraryItem {
}
