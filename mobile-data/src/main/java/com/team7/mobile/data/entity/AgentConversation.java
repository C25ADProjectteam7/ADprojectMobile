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
@Entity
@Table(name = "agent_conversations")
public class AgentConversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Nullable: an agent turn isn't always tied to a specific trip yet (e.g. before one exists). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** JSON-encoded tool calls the Agent made while producing this turn, if any. */
    @Column(columnDefinition = "TEXT")
    private String toolCalls;

    private Integer tokenCount;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Trip getTrip() {
        return trip;
    }

    public void setTrip(Trip trip) {
        this.trip = trip;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(String toolCalls) {
        this.toolCalls = toolCalls;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public enum Role {
        USER, ASSISTANT, SYSTEM
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
