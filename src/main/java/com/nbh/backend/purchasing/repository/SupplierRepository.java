package com.nbh.backend.purchasing.repository;

import com.nbh.backend.purchasing.entity.Supplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {
    List<Supplier> findByDeletedFalseOrderByName();
}
