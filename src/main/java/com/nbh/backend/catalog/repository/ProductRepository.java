package com.nbh.backend.catalog.repository;

import com.nbh.backend.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsBySlug(String slug);

    /**
     * Every product filed directly under this category (not its subcategories'), live, deleted and demo alike, with all of
     * their categories loaded: moving a category's products has to take the deleted ones along, since they are what stops
     * it being deleted.
     */
    @Query("""
            SELECT DISTINCT p FROM Product p JOIN FETCH p.categories
            WHERE p.id IN (SELECT p2.id FROM Product p2 JOIN p2.categories c WHERE c.id = :categoryId)
            """)
    List<Product> findAllFiledDirectlyIn(@Param("categoryId") UUID categoryId);

    /**
     * Products with a variant that is filed under this category as the variant's OWN category (a range whose categories differ,
     * V68), with all their categories loaded. A category's products, for a move, are these plus {@link #findAllFiledDirectlyIn}.
     */
    @Query("""
            SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.categories
            WHERE p.id IN (SELECT v.product.id FROM ProductVariant v WHERE v.category.id = :categoryId)
            """)
    List<Product> findAllWithVariantsFiledIn(@Param("categoryId") UUID categoryId);

    /** These products with all their categories loaded in one query, for a bulk action over many ticked products. */
    @Query("SELECT DISTINCT p FROM Product p LEFT JOIN FETCH p.categories WHERE p.id IN :ids")
    List<Product> findAllWithCategoriesByIdIn(@Param("ids") Collection<UUID> ids);

    /**
     * A targeted column update, deliberately bypassing the managed entity (not
     * {@code product.setPublished(...)} + {@code save()}) so publishing does NOT bump
     * {@code updatedAt} — see {@link com.nbh.backend.catalog.service.ProductService#setPublished}
     * for why that matters.
     */
    @Modifying
    @Query("UPDATE Product p SET p.isPublished = :published, p.publishedAt = :publishedAt WHERE p.id = :id")
    void updatePublishedState(@Param("id") UUID id, @Param("published") boolean published,
                               @Param("publishedAt") Instant publishedAt);

    /**
     * Marks the product as changed without loading it: for changes made to its variants from outside the product editor
     * (a variant job, a colour renamed everywhere, several prices at once). The editor's Save compares this with the
     * {@code updatedAt} it started from, so a page opened before the change is told to reload instead of saving its
     * older list of variants over the new one (which would remove or rename them back).
     */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE Product p SET p.updatedAt = :at WHERE p.id = :id")
    void touch(@Param("id") UUID id, @Param("at") Instant at);

    Optional<Product> findBySlugAndIsPublishedTrueAndDeletedFalse(String slug);

    /**
     * Public storefront browse — published/not-deleted only. {@code q} and
     * {@code categorySlug} are optional (a null parameter short-circuits its
     * own clause). A product tagged with a subcategory also matches its parent
     * category's slug (Phase 3, 2026-09-24 — products can belong to several
     * categories now; {@code c.parent} rolls a subcategory match up to its
     * parent the same way the old category/subcategory pair used to).
     *
     * {@code CAST(:q AS string)} is required — found live: with a null
     * {@code q}, PostgreSQL's JDBC driver can't infer a type for that bind
     * parameter from its position inside {@code LOWER(CONCAT(...))} alone and
     * defaults it to {@code bytea}, so every call with no search term (not
     * just an actual search) failed with "function lower(bytea) does not
     * exist". The explicit cast forces varchar, which the null-check on the
     * same parameter never needed since {@code IS NULL} doesn't care about type.
     *
     * Explicit {@code LEFT JOIN c.parent parent} is required — found live (Phase 3,
     * 2026-09-24, the exact same landmine {@link #searchAdmin}'s old category/
     * subcategory version warned about): an implicit path navigation like
     * {@code c.parent.slug} in a WHERE clause compiles to an INNER join, which
     * silently drops every product with NO category at all, since that inner
     * join can never succeed when {@code c} itself is null from the outer LEFT JOIN.
     */
    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN p.categories c
            LEFT JOIN c.parent parent
            WHERE p.isPublished = true AND p.deleted = false
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (:q IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR LOWER(p.shortDescription) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
              AND (:categorySlug IS NULL OR c.slug = :categorySlug OR parent.slug = :categorySlug)
              AND (:popular IS NULL OR p.isPopular = :popular)
              AND (:newArrival IS NULL OR p.isNewArrival = :newArrival)
              AND (:deal IS NULL OR :deal = false OR EXISTS (
                        SELECT 1 FROM ProductVariant v
                        WHERE v.product = p AND v.dealPrice IS NOT NULL AND v.dealEndsAt > CURRENT_TIMESTAMP))
            """)
    Page<Product> searchPublished(@Param("q") String q, @Param("categorySlug") String categorySlug,
                                   @Param("deal") Boolean deal, @Param("popular") Boolean popular,
                                   @Param("newArrival") Boolean newArrival, Pageable pageable);

    /**
     * Staff product list (never a package: packages are managed under Packages, owner 2026-10-19): every optional filter
     * short-circuits when null. {@code q} also matches a
     * variant SKU or name. With {@code among}, only the products in {@code ids} (the search box's
     * matches from {@link com.nbh.backend.catalog.service.ProductSearch}; {@code q} is then null). {@code options}:
     * SINGLE = products with at most one live variant (the Products list), RANGE = two or more (the Variants list,
     * owner 2026-10-17); null = all. {@code stock} is IN (something sellable) or OUT (nothing sellable); the
     * same sellable definition as the public catalogue (on hand minus reserved, at SHOP — branches/
     * locations were removed 2026-09-24, founder decision, see HANDOFF.md). {@code categoryId}
     * matches a product tagged with that category directly, or with one of its subcategories
     * (Phase 3, 2026-09-24 — products can belong to several categories now).
     * Same {@code CAST(... AS string)} null-parameter fix as {@link #searchPublished}.
     *
     * <p>Mock data is only ever meant to be visible while {@code MockDataSeederService} considers
     * it "enabled" (any mock product currently published) — the demo toggle's {@code disable()}
     * flips {@code isPublished=false} on every mock product in lockstep, so the final clause
     * excludes them regardless of whatever {@code :published} filter the admin screen itself has
     * selected.
     */
    @Query("""
            SELECT DISTINCT p FROM Product p
            LEFT JOIN p.categories c
            LEFT JOIN c.parent parent
            WHERE p.deleted = :deleted
              AND NOT EXISTS (SELECT 1 FROM ProductVariant kv WHERE kv.product = p AND kv.isPackage = true)
              AND (:q IS NULL
                   OR LOWER(p.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                   OR EXISTS (SELECT 1 FROM ProductVariant v
                              WHERE v.product = p AND (
                                  LOWER(v.sku) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
                                  OR LOWER(v.variantName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))))
              AND (:among = false OR p.id IN :ids)
              AND (:noPhoto = false
                   OR NOT EXISTS (SELECT 1 FROM ProductImage pi WHERE pi.product = p))
              AND (:options IS NULL
                   OR (CAST(:options AS string) = 'SINGLE'
                       AND (SELECT COUNT(ov) FROM ProductVariant ov WHERE ov.product = p AND ov.deleted = false) <= 1)
                   OR (CAST(:options AS string) = 'RANGE'
                       AND (SELECT COUNT(ov) FROM ProductVariant ov WHERE ov.product = p AND ov.deleted = false) > 1))
              AND (:categoryId IS NULL OR c.id = :categoryId OR parent.id = :categoryId)
              AND (:published IS NULL OR p.isPublished = :published)
              AND (:popular IS NULL OR p.isPopular = :popular)
              AND (:newArrival IS NULL OR p.isNewArrival = :newArrival)
              AND (:stock IS NULL
                   OR (CAST(:stock AS string) = 'IN' AND EXISTS (
                            SELECT 1 FROM StockBalance b
                            WHERE b.variant.product = p AND b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
                              AND (b.onHandQty - b.reservedQty) > 0))
                   OR (CAST(:stock AS string) = 'OUT' AND NOT EXISTS (
                            SELECT 1 FROM StockBalance b
                            WHERE b.variant.product = p AND b.account = com.nbh.backend.stock.entity.StockAccount.SHOP
                              AND (b.onHandQty - b.reservedQty) > 0)))
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
            """)
    Page<Product> searchAdmin(@Param("q") String q,
                               @Param("among") boolean among,
                               @Param("ids") java.util.Collection<UUID> ids,
                               @Param("categoryId") UUID categoryId,
                               @Param("published") Boolean published,
                               @Param("stock") String stock,
                               @Param("popular") Boolean popular,
                               @Param("newArrival") Boolean newArrival,
                               @Param("deleted") boolean deleted,
                               @Param("options") String options,
                               @Param("noPhoto") boolean noPhoto,
                               Pageable pageable);

    /** Shares a category (directly, or via a shared parent) with the given product, excluding itself — the "related products" rail. */
    @Query("""
            SELECT DISTINCT p FROM Product p
            JOIN p.categories c
            LEFT JOIN c.parent parent
            WHERE p.isPublished = true AND p.deleted = false AND p.id <> :excludeId
              AND ((p.isMockData = true AND EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)) OR (p.isMockData = false AND NOT EXISTS (SELECT 1 FROM Product mp WHERE mp.isMockData = true AND mp.isPublished = true)))
              AND (c.id IN :categoryIds OR parent.id IN :categoryIds)
            """)
    List<Product> findRelated(@Param("categoryIds") List<UUID> categoryIds,
                               @Param("excludeId") UUID excludeId,
                               Pageable pageable);

    /**
     * Real products with no description of their own and no AI draft waiting, published ones first: who a
     * "write descriptions only" run works on. (Demo products are left out; they always have text.)
     */
    @Query("""
            SELECT p.id FROM Product p
            WHERE p.deleted = false AND p.isMockData = false
              AND (p.description IS NULL OR TRIM(p.description) = '')
              AND NOT EXISTS (SELECT 1 FROM ProductDescriptionDraft d WHERE d.productId = p.id)
            ORDER BY p.isPublished DESC, p.name ASC
            """)
    java.util.List<java.util.UUID> idsWithoutDescription(org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT COUNT(p) FROM Product p
            WHERE p.deleted = false AND p.isMockData = false
              AND (p.description IS NULL OR TRIM(p.description) = '')
              AND NOT EXISTS (SELECT 1 FROM ProductDescriptionDraft d WHERE d.productId = p.id)
            """)
    long countWithoutDescription();

    /**
     * How many real products "Tidy colours & sizes" still lists: not looked at yet, with two or more live (non-package)
     * variants of which at least one has neither a colour nor a size. The same rule as
     * {@code TidyOptionsService.waiting}, as one COUNT so the dashboard does not load every variant of the catalogue
     * on each refresh.
     */
    @Query("""
            SELECT COUNT(p) FROM Product p
            WHERE p.deleted = false AND p.isMockData = false AND p.optionsReviewedAt IS NULL
              AND (SELECT COUNT(v) FROM ProductVariant v
                   WHERE v.product = p AND v.deleted = false AND v.isPackage = false) >= 2
              AND EXISTS (SELECT 1 FROM ProductVariant w
                          WHERE w.product = p AND w.deleted = false AND w.isPackage = false
                            AND w.optionColor IS NULL AND w.optionSize IS NULL)
            """)
    long countWaitingForOptionsReview();
}
