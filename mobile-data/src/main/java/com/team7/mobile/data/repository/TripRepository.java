package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 行程数据访问接口
 * 提供按用户 ID、状态、日期范围查询行程的常用方法
 */
// TODO: 定义自定义查询 — findByUserId(Long), findByStatusIn(List), findByStartDateBetween()
@Repository
public interface TripRepository {
}
