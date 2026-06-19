package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.RiskWarning;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RiskWarningRepository extends JpaRepository<RiskWarning, Integer> {

    List<RiskWarning> findByStatusOrderByCreateTimeDesc(Integer status);

    Page<RiskWarning> findByStatus(Integer status, Pageable pageable);

    List<RiskWarning> findByUserIdOrderByCreateTimeDesc(Integer userId);

    List<RiskWarning> findByUserIdAndStatus(Integer userId, Integer status);

    Page<RiskWarning> findByUserIdAndStatus(Integer userId, Integer status, Pageable pageable);
}