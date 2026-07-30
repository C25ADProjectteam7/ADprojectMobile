package com.team7.mobile.business.service;

import org.springframework.stereotype.Service;

/**
 * 文件存储服务 — 处理发票图片、头像等文件的上传和下载
 * <p>
 * 存储路径：/var/www/adproject-mobile/images/{category}/
 * 支持：发票扫描件(receipts)、地点图片(places)、用户头像(avatars)
 * 文件命名：UUID + 原始扩展名，防止文件名冲突
 */
// TODO: 实现 uploadFile(), downloadFile(), deleteFile(), 文件大小和类型校验
@Service
public class FileStorageService {
}
