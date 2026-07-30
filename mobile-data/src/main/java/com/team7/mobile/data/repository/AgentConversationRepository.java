package com.team7.mobile.data.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Agent 对话历史访问接口
 * 按用户和行程查询对话记录，支持分页和时间排序
 */
// TODO: 定义 — findByUserIdAndTripIdOrderByCreatedAt(), findByUserId()
@Repository
public interface AgentConversationRepository extends JpaRepository<com.team7.mobile.data.entity.AgentConversation, Long> {
}
