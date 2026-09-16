package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.VariantBarcode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface VariantBarcodeRepository extends JpaRepository<VariantBarcode, UUID> {
    List<VariantBarcode> findByVariantId(UUID variantId);
}
