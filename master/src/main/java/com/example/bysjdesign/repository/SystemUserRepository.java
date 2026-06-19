package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.SystemUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SystemUserRepository extends JpaRepository<SystemUser, Integer> {
    Optional<SystemUser> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByStatus(Integer status);

    long countByRoleAndStatus(String role, Integer status);

    List<SystemUser> findAllByOrderByStatusDescUserIdAsc();
}
