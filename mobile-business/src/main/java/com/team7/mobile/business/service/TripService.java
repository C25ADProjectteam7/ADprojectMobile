package com.team7.mobile.business.service;

import org.springframework.stereotype.Service;

/**
 * 行程管理服务 — 行程的创建、查询、修改、取消
 * <p>
 * 创建流程：
 * 1. 接收 TripRequest → 验证日期范围、预算 > 0
 * 2. 调用 AgentOrchestrator 让 Agent 规划行程
 * 3. Agent 返回完整 Itinerary → 保存到数据库
 * 4. 返回行程详情
 * <p>
 * 修改流程（用户与 Agent 对话修改）：
 * 1. 接收修改请求 → 查找当前行程
 * 2. 调用 AgentOrchestrator.replan() 重新规划
 * 3. 更新数据库中的行程计划
 */
// TODO: 实现 createTrip(), getTripById(), getUserTrips(), updateTrip(), cancelTrip()
@Service
public class TripService {
}
