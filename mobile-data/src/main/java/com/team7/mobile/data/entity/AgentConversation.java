package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Agent 对话记录实体 — 映射 agent_conversations 表
 * <p>
 * 保存用户与行程管家 Agent 的完整对话历史
 * role: USER(用户输入) / ASSISTANT(Agent 回复) / SYSTEM(系统提示)
 * toolCalls: JSON 格式，记录 Agent 本次调用了哪些工具及参数
 * 用于：上下文续接、对话回放、Agent 行为审计
 */
// TODO: 定义字段 — id, user(FK), trip(FK nullable), role, content(TEXT)
// TODO: toolCalls(JSON nullable), tokenCount, createdAt
public class AgentConversation {
}
