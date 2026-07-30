package com.team7.mobile.business.service;

import org.springframework.stereotype.Service;

/**
 * 费用报销服务 — 报销申请的提交、查询、审核
 * <p>
 * 员工侧：
 * - 提交报销（含发票图片上传后的 URL 引用）
 * - 查看本人报销历史
 * - OCR 扫描发票自动填充报销信息（调用 OCR 服务）
 * <p>
 * 财务侧（Web 端通过 API 调用）：
 * - 查看待审核报销列表
 * - 审批/驳回报销申请
 */
// TODO: 实现 submitExpense(), getUserExpenses(), processReceiptOCR(), approveExpense()
@Service
public class ExpenseService {
}
