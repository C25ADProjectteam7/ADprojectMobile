package com.team7.mobile.data.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用/报销实体 — 映射 expenses 表
 * <p>
 * 员工出差后提交报销申请，支持拍照扫描发票（receiptUrl 指向上传的图片路径）
 * category: FLIGHT/HOTEL/MEAL/TRANSPORT/OTHER
 * 审核流程：SUBMITTED → (财务审核) → APPROVED/REJECTED
 */
// TODO: 定义字段 — id, trip(FK), user(FK), category, amount, currency
// TODO: description, receiptUrl(发票图片), status, submittedAt, reviewedAt, reviewNote
public class Expense {
}
