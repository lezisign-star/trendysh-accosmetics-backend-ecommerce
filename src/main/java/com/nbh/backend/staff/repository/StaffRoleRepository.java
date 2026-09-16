package com.nbh.backend.staff.repository;

import com.nbh.backend.staff.entity.StaffRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffRoleRepository extends JpaRepository<StaffRole, UUID> {
    Optional<StaffRole> findByName(String name);
    List<StaffRole> findByIsDefaultTrue();
}
