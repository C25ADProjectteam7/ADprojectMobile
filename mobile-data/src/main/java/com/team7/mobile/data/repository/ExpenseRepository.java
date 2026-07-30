package com.team7.mobile.data.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 费用/报销数据访问接口
 * 提供按用户、行程、状态、费用类别查询的方法
 */
// TODO: 定义自定义查询 — findByTripIdAndUserId, findByStatus, findByCategory
@Repository
public interface ExpenseRepository extends JpaRepository<com.team7.mobile.data.entity.Expense, Long> {
}
