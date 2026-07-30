package com.team7.mobile.data.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 酒店数据缓存访问接口
 * 按城市、入住日期、价格区间查询
 */
// TODO: 定义 — findByCity(), findByCityAndPriceBetween()
@Repository
public interface HotelRepository extends JpaRepository<com.team7.mobile.data.entity.Hotel, Long> {
}
