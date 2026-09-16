package com.nbh.backend.staff.repository;

import com.nbh.backend.staff.entity.StaffBranchAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StaffBranchAssignmentRepository
        extends JpaRepository<StaffBranchAssignment, StaffBranchAssignment.Id> {
    List<StaffBranchAssignment> findByUserId(UUID userId);
}
