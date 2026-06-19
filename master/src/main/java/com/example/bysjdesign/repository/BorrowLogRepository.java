package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.BorrowLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@Repository
public interface BorrowLogRepository extends JpaRepository<BorrowLog, Long> {

    List<BorrowLog> findByUserId(Integer userId);

    List<BorrowLog> findByUserIdAndBorrowDateAfter(Integer userId, LocalDate startDate);

    @Query("SELECT DISTINCT b.userId FROM BorrowLog b WHERE b.borrowDate >= :since")
    List<Integer> findDistinctUserIdsByBorrowDateAfter(@Param("since") Date since);
}
