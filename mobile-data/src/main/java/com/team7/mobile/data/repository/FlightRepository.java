package com.team7.mobile.data.repository;

import org.springframework.stereotype.Repository;

/**
 * 航班数据缓存访问接口
 * 按出发/到达机场、日期、航空公司查询缓存的航班数据
 * 缓存过期后由定时任务清理并重新拉取
 */
// TODO: 定义 — findByDepartureAndArrivalAndDate(), deleteByCachedAtBefore()
@Repository
public interface FlightRepository {
}
