package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.TaskExecutionState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TaskExecutionStateRepository extends JpaRepository<TaskExecutionState, String> {
    java.util.List<TaskExecutionState> findAllByOrderByTaskKeyAsc();
}
