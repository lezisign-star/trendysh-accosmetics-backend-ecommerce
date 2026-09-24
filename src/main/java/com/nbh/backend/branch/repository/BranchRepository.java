package com.nbh.backend.branch.repository;

import com.nbh.backend.branch.entity.Branch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {
    Optional<Branch> findByCode(String code);

    /** The main branch takes every order by default; other active branches only fill a stock gap. */
    Optional<Branch> findByIsDefaultOnlineFulfilmentTrue();

    /** Fallback order when the main branch is short on a line — deterministic (by code), not by preference. */
    List<Branch> findByIsActiveTrueOrderByCode();

    List<Branch> findAllByOrderByName();
}
