package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.RiskWarning;
import com.example.bysjdesign.campus.entity.WarningLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;

@Repository
public interface WarningLogRepository extends JpaRepository<WarningLog, Integer> {
    List<WarningLog> findByStatus(Integer status);
    long countByStatus(Integer status);
    @Query("SELECT w.type, COUNT(w) FROM WarningLog w GROUP BY w.type")
    List<Object[]> countByType();

    List<WarningLog> findByUserIdAndStatus(Long userId, String active);

    List<RiskWarning> findByUserId(Long userId);
}