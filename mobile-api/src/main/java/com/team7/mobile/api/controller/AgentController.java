package com.team7.mobile.api.controller;

import org.springframework.web.bind.annotation.*;

/**
 * Agent 对话 API 控制器 — 用户与行程管家 Agent 的交互入口
 * <p>
 * 接口：
 * - POST /api/agent/chat          — 向 Agent 发送消息（文本或结构化指令）
 *   请求：{ "message": "把我新加坡的航班改到下午", "tripId": 123 }
 *   响应：{ "reply": "好的，我来帮你查询下午的航班...", "actions": [...] }
 * - GET  /api/agent/conversations/{tripId} — 获取与 Agent 的对话历史
 * <p>
 * Agent 处理流程：
 * 用户消息 → AgentOrchestrator → Python Agent (DeepSeek LLM) → 回复 + 工具调用
 */
// TODO: 定义 Agent 对话接口，支持流式返回（SSE）可选
@RestController
@RequestMapping("/api/agent")
public class AgentController {
}
