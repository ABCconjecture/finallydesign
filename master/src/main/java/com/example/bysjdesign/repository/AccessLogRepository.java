package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.AccessLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Repository
public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    List<AccessLog> findByUserId(Integer userId);

    List<AccessLog> findByUserIdAndEntryTimeAfter(Integer userId, LocalDateTime atStartOfDay);

    @Query("SELECT DISTINCT a.userId FROM AccessLog a WHERE a.entryTime >= :since")
    List<Integer> findDistinctUserIdsByEntryTimeAfter(@Param("since") Date since);

    @Query(value = "SELECT a.user_id, COUNT(*) FROM access_log a " +
            "WHERE a.location_type = '宿舍' AND HOUR(a.entry_time) >= 23 " +
            "AND a.entry_time BETWEEN :start AND :end GROUP BY a.user_id", nativeQuery = true)
    List<Object[]> lateReturnSummary(@Param("start") Date start, @Param("end") Date end);

    @Query(value = "SELECT a.user_id, COUNT(*) FROM access_log a " +
            "WHERE a.location_type = '教学楼' AND a.entry_time BETWEEN :start AND :end GROUP BY a.user_id", nativeQuery = true)
    List<Object[]> classroomSummary(@Param("start") Date start, @Param("end") Date end);
}
