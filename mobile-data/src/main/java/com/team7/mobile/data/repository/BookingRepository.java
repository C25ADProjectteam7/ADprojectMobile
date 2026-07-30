package com.team7.mobile.data.repository;

import org.springframework.stereotype.Repository;

/**
 * 预订记录数据访问接口
 * 提供按用户、行程、预订状态、预订类型查询的方法
 */
// TODO: 定义自定义查询 — findByTripId(Long), findByUserId(Long), findByStatus(Boolean)
@Repository
public interface BookingRepository {
}
