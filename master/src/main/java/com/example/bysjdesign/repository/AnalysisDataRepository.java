package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.AnalysisData;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalysisDataRepository extends JpaRepository<AnalysisData, Long> {

    List<AnalysisData> findByUserId(Integer userId, Pageable pageable);

    List<AnalysisData> findByUserIdAndAnalysisDateBetween(Integer userId, LocalDate start, LocalDate end);

    Optional<AnalysisData> findByUserIdAndAnalysisDate(Integer userId, LocalDate analysisDate);

    Optional<AnalysisData> findFirstByUserIdOrderByAnalysisDateDesc(Integer userId);

    Optional<AnalysisData> findFirstByUserIdOrderByAnalysisDateDescIdDesc(Integer userId);

    @Query("SELECT a FROM AnalysisData a WHERE a.id IN (" +
            "  SELECT MAX(a2.id) FROM AnalysisData a2 WHERE a2.userId IN :userIds GROUP BY a2.userId" +
            ")")
    List<AnalysisData> findLatestAnalysisForUsers(@Param("userIds") List<Integer> userIds);

    @Query("SELECT DISTINCT a.userId FROM AnalysisData a WHERE a.updateTime >= :since")
    List<Integer> findDistinctUserIdsByUpdateTimeAfter(@Param("since") LocalDateTime since);

    boolean existsByUpdateTimeAfter(LocalDateTime since);

    @Query("SELECT COUNT(a) FROM AnalysisData a WHERE a.riskScore > 70")
    long countHighRiskUsers();

    @Query("SELECT AVG(a.healthScore) FROM AnalysisData a")
    Double getGlobalAverageHealthScore();
}
