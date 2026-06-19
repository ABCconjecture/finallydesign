package com.example.bysjdesign.repository;

import com.example.bysjdesign.campus.entity.CampusUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CampusUserRepository extends JpaRepository<CampusUser, Integer> {
    Optional<CampusUser> findByStudentId(String studentId);

    List<CampusUser> findByNameContaining(String name);
}
