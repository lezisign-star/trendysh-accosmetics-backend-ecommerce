package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BrandRepository extends JpaRepository<Brand, UUID> {
}
