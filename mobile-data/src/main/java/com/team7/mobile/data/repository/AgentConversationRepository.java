package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.AgentConversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Agent 对话历史访问接口
 * 按用户和行程查询对话记录，支持分页和时间排序
 */
@Repository
public interface AgentConversationRepository extends JpaRepository<AgentConversation, Long> {

    List<AgentConversation> findByUserIdAndTripIdOrderByCreatedAtAsc(Long userId, Long tripId);

    /**
     * Most recent turns first, capped by the given Pageable - use this (not
     * the unbounded findByUserIdAndTripIdOrderByCreatedAtAsc above) when
     * feeding history to the Agent, which only ever uses the last N turns
     * anyway (agent-ml-service's config.MAX_CONVERSATION_HISTORY). Caller
     * must reverse the result back to chronological order.
     */
    List<AgentConversation> findByUserIdAndTripIdOrderByCreatedAtDesc(Long userId, Long tripId, Pageable pageable);

    /**
     * Oldest turns first, capped by the given Pageable - PageRequest.of(0, N)
     * gives exactly "the oldest N turns", used to fetch the turns that need
     * folding into Trip.conversationSummary.
     */
    List<AgentConversation> findByUserIdAndTripIdOrderByCreatedAtAsc(Long userId, Long tripId, Pageable pageable);

    long countByUserIdAndTripId(Long userId, Long tripId);

    List<AgentConversation> findByUserIdOrderByCreatedAtAsc(Long userId);
}
