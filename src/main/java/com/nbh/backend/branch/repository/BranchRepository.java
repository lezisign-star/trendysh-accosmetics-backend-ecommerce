package com.nbh.backend.branch.repository;

import com.nbh.backend.branch.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {
    Optional<Branch> findByCode(String code);

    /** Per the founder's confirmed decision (session 5): online orders are fulfilled by a fixed default branch for now. */
    Optional<Branch> findByIsDefaultOnlineFulfilmentTrue();
}
