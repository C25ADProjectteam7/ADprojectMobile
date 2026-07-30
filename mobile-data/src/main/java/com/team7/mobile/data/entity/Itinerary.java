package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * 行程单实体 — 映射 itineraries 表
 * <p>
 * 一个行程有多个 itinerary（每天一个），每个 itinerary 有多个 itinerary_item
 * 结构：Trip (1) → Itinerary (N) → ItineraryItem (N)
 */
// TODO: 定义字段 — id, trip(FK), dayNumber, date, notes, generatedByAgent
// TODO: 添加 @OneToMany → ItineraryItem
@Entity
@Table(name = "itineraries")
public class Itinerary {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

}
