package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行程实体 — 映射 trips 表
 * <p>
 * 一个用户可创建多个行程，每个行程关联多条 itinerary、booking、expense
 * 行程由 Agent 根据用户需求自动规划，或用户手动创建
 */
// TODO: 定义字段 — id, user(FK), title, destination, startDate, endDate, budgetTotal, status, createdAt, updatedAt
// TODO: 添加 @ManyToOne → User, @OneToMany → Itinerary/Booking/Expense
@Entity
@Table(name = "trips")
public class Trip {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

}
