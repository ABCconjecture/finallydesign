package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.TaskExecutionLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskExecutionLogRepository extends JpaRepository<TaskExecutionLog, Long> {
    Page<TaskExecutionLog> findByTaskKeyOrderByIdDesc(String taskKey, Pageable pageable);

    List<TaskExecutionLog> findByTaskKeyOrderByIdDesc(String taskKey);

    TaskExecutionLog findFirstByTaskKeyAndStatusOrderByIdDesc(String taskKey, String status);
}
