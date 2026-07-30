package com.team7.mobile.data.repository;

import com.team7.mobile.data.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 用户数据访问接口 — Spring Data JPA 自动生成 CRUD 实现
 * 继承 JpaRepository<User, Long> 获得 save/findById/findAll/delete 等方法
 */
// TODO: 定义自定义查询 — findByUsername(String), findByEmail(String), existsByUsername(String)
@Repository
public interface UserRepository {
}
