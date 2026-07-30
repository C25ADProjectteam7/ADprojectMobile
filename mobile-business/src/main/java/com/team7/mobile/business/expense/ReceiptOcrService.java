package com.team7.mobile.business.expense;

import org.springframework.stereotype.Service;

/**
 * 发票 OCR 识别服务 — 拍照扫描发票自动提取信息
 * <p>
 * 流程：
 * 1. 接收发票图片（来自 Android 端拍照上传）
 * 2. 调用 OCR 引擎识别文字 → 提取金额、日期、商家名称
 * 3. 返回结构化的报销信息，自动填充 Expense 表单
 * <p>
 * OCR 实现选项：
 * - Tesseract OCR（开源免费）
 * - 或调用 Python ML 服务中的 OCR 模块
 */
// TODO: 实现 OCR 识别，提取金额/日期/商家，返回结构化数据
@Service
public class ReceiptOcrService {
}
