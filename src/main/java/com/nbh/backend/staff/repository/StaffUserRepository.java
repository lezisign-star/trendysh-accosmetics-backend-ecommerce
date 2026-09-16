package com.nbh.backend.staff.repository;

import com.nbh.backend.staff.entity.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StaffUserRepository extends JpaRepository<StaffUser, UUID> {
    Optional<StaffUser> findByEmailIgnoreCaseAndDeletedFalse(String email);
    boolean existsByEmailIgnoreCase(String email);
}
