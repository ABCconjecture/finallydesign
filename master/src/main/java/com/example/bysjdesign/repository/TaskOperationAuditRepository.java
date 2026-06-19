package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.TaskOperationAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TaskOperationAuditRepository extends JpaRepository<TaskOperationAudit, Long>, JpaSpecificationExecutor<TaskOperationAudit> {

    Page<TaskOperationAudit> findByTaskKeyOrderByIdDesc(String taskKey, Pageable pageable);

    TaskOperationAudit findFirstByTaskKeyAndActionAndResultOrderByIdDesc(String taskKey, String action, String result);

    java.util.Optional<TaskOperationAudit> findByIdAndTaskKey(Long id, String taskKey);
}
