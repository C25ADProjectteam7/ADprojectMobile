package com.team7.mobile.data.config;

import org.springframework.context.annotation.Configuration;

/**
 * 数据库连接配置 — MySQL 8.0 数据源配置
 * <p>
 * 读取 application.properties 中的 spring.datasource.* 配置项
 * 包括连接池（HikariCP）参数：最大连接数、超时时间等
 * 生产环境密码通过环境变量注入，不写在配置文件中
 */
// TODO: HikariCP 连接池参数调优，开发/生产环境 Profile 分离
@Configuration
public class DataSourceConfig {
}
