package com.team7.mobile.business.agent;

import org.springframework.stereotype.Service;

/**
 * Agent 编排器 — Spring Boot 与 Python Agent 服务之间的桥梁
 * <p>
 * 职责：将用户的行程需求转发给 Python FastAPI Agent 服务，等待 Agent 完成推理
 * 并返回结构化行程数据
 * <p>
 * 核心流程：
 * 1. generateItinerary(TripRequest):
 *    → 将请求转为 Agent 可理解的 prompt → 调用 Python Agent → 返回完整行程 JSON
 *
 * 2. modifyItinerary(tripId, userMessage):
 *    → 加载历史对话 → 追加用户新请求 → 调用 Agent re-plan → 返回更新后的行程
 *
 * 3. analyzeBudget(tripRequest):
 *    → 调用 ML 预算分配模型 → 获得机票/酒店/餐饮分配建议 → 传给 Agent 用于约束搜索
 * <p>
 * Python Agent 地址通过配置项 agent.ml.service.url 指定（Docker 内部网络: http://agent-ml:8000）
 */
// TODO: 实现与 Python Agent 的 HTTP 通信，超时重试，错误处理，对话历史管理
@Service
public class AgentOrchestrator {
}
