package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

/**
 * 用户画像仓库接口
 * 职责：负责 UserProfile 实体类与数据库表 user_profile 的映射与交互
 */
@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Integer> {

    /**
     * 根据用户ID查找画像
     * 注意：参数类型为 Integer，需与 CampusUser 实体类的 userId 类型保持一致
     */
    UserProfile findByUserId(Integer userId);

    /**
     * 根据聚类ID查找用户画像列表
     * 用于在用户画像页面展示特定群体的学生
     */
    List<UserProfile> findByClusterId(Integer clusterId);

    /**
     * 统计每个聚类中的用户数量
     * 用于前端仪表盘的聚类分布图表显示
     */
    @Query("SELECT p.clusterId, COUNT(p) FROM UserProfile p GROUP BY p.clusterId")
    List<Object[]> countByCluster();

    @Query("SELECT DISTINCT p.userId FROM UserProfile p WHERE p.updateTime >= :since")
    List<Integer> findDistinctUserIdsByUpdateTimeAfter(@Param("since") Date since);
}
