package com.nbh.backend.dealphotos;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DealPhotoRepository extends JpaRepository<DealPhoto, UUID> {

    List<DealPhoto> findByStatusOrderByCreatedAtDesc(DealPhoto.Status status);

    long countByStatus(DealPhoto.Status status);

    List<DealPhoto> findByVariantIdAndStatus(UUID variantId, DealPhoto.Status status);

    @Query("SELECT d FROM DealPhoto d WHERE d.status = :status AND d.variant.id IN :variantIds")
    List<DealPhoto> findByStatusAndVariantIdIn(@Param("status") DealPhoto.Status status,
                                               @Param("variantIds") Collection<UUID> variantIds);

    /**
     * Products on a live flash deal that have never had a deal picture tried (any status counts as tried, so a failure
     * or a rejection is not retried on its own): real, published, sellable, not kits, with a photo to start from (one
     * without is left until a photo is added), the deal ending soonest first.
     */
    @Query(value = """
            SELECT v.id FROM product_variant v
            JOIN product p ON p.id = v.product_id
            WHERE v.deal_price IS NOT NULL AND v.deal_ends_at > now()
              AND v.is_active AND NOT v.deleted AND NOT v.is_kit AND v.selling_price IS NOT NULL
              AND p.is_published AND NOT p.deleted AND NOT p.is_mock_data
              AND EXISTS (SELECT 1 FROM product_image i WHERE i.product_id = v.product_id)
              AND NOT EXISTS (SELECT 1 FROM deal_photo d WHERE d.variant_id = v.id)
            ORDER BY v.deal_ends_at
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> variantsWaitingForAPicture(@Param("limit") int limit);
}
