package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.CategoryDto;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.service.CategoryService;
import com.nbh.backend.common.exception.ConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Two levels only: top-level categories and their subcategories (a subcategory cannot have children).
 * One category holds many subcategories; products are filed under a category and, optionally, one of
 * its subcategories.
 */
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final CategoryService categoryService;

    public CategoryController(CategoryRepository categoryRepository, CategoryService categoryService) {
        this.categoryRepository = categoryRepository;
        this.categoryService = categoryService;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CategoryDto> list() {
        // A product counts once per category even when it is in it two ways: filed under it, and through a variant that
        // has it as its own category (a range whose categories differ).
        Map<UUID, Set<UUID>> productsIn = new HashMap<>();
        for (Object[] row : categoryRepository.liveProductCategoryPairs()) {
            productsIn.computeIfAbsent((UUID) row[0], k -> new HashSet<>()).add((UUID) row[1]);
        }
        for (Object[] row : categoryRepository.liveVariantCategoryPairs()) {
            productsIn.computeIfAbsent((UUID) row[0], k -> new HashSet<>()).add((UUID) row[1]);
        }
        Map<UUID, Long> direct = new HashMap<>();
        productsIn.forEach((categoryId, products) -> direct.put(categoryId, (long) products.size()));
        boolean demoOn = categoryRepository.demoDataOn();
        List<Category> all = categoryRepository.findAll().stream()
                .filter(c -> (c.getParent() == null && !c.isMockData()) || c.isMockData() == demoOn)
                .toList();
        // A top-level category's shown count rolls up its subcategories' products too (a product
        // in several categories, Phase 3 2026-09-24 — direct counts alone would undercount a
        // parent whose products are all filed one level down, under its children).
        Map<UUID, Long> rolledUp = new HashMap<>();
        for (Category c : all) {
            long own = direct.getOrDefault(c.getId(), 0L);
            rolledUp.merge(c.getId(), own, Long::sum);
            if (c.getParent() != null) {
                rolledUp.merge(c.getParent().getId(), own, Long::sum);
            }
        }
        return all.stream()
                .sorted(Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName, String.CASE_INSENSITIVE_ORDER))
                .map(c -> CategoryDto.from(c, rolledUp.getOrDefault(c.getId(), 0L), direct.getOrDefault(c.getId(), 0L)))
                .toList();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping
    @Transactional
    public CategoryDto create(@RequestBody CreateCategoryRequest request) {
        String name = cleanName(request.name());
        Category parent = null;
        if (request.parentId() != null) {
            parent = categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new EntityNotFoundException("No category: " + request.parentId()));
            if (parent.getParent() != null) {
                throw new IllegalArgumentException("Subcategories can't have their own subcategories — pick a top-level category as the parent");
            }
        }
        categoryService.refuseSameNameBeside(name, parent, null);
        Category category = new Category();
        category.setName(name);
        category.setSlug(uniqueSlug(name, null));
        category.setParent(parent);
        return CategoryDto.from(categoryRepository.save(category));
    }

    /** Partial update: only non-null fields change. A category goes under another one with {@code POST /{id}/move}. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/{id}")
    @Transactional
    public CategoryDto update(@PathVariable UUID id, @RequestBody UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No category: " + id));
        if (request.name() != null) {
            String name = cleanName(request.name());
            if (!name.equals(category.getName())) {
                categoryService.refuseSameNameBeside(name, category.getParent(), category.getId());
                category.setName(name);
                category.setSlug(uniqueSlug(name, category.getId()));
            }
        }
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());
        if (request.imageUrl() != null) category.setImageUrl(cleanImageUrl(request.imageUrl()));
        if (request.isPublished() != null) category.setPublished(request.isPublished());
        return CategoryDto.from(categoryRepository.save(category));
    }

    /** Only an empty category can go: no subcategories and no products (deleted ones count too). */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable UUID id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No category: " + id));
        long children = categoryRepository.countByParentId(id);
        if (children > 0) {
            throw new ConflictException("\"" + category.getName() + "\" still has " + children
                    + (children == 1 ? " subcategory" : " subcategories") + " — delete or move them first");
        }
        long products = categoryRepository.countProductsReferencing(id);
        if (products > 0) {
            throw new ConflictException("\"" + category.getName() + "\" still has " + products
                    + (products == 1 ? " product" : " products") + " — move them to another category first");
        }
        long variants = categoryRepository.countVariantsFiledUnder(id);
        if (variants > 0) {
            throw new ConflictException("\"" + category.getName() + "\" still has " + variants
                    + (variants == 1 ? " variant" : " variants") + " filed under it (a range whose categories differ)"
                    + " — move its products to another category first");
        }
        categoryRepository.delete(category);
    }

    /**
     * Moves every product filed directly under this category (its subcategories' products stay where they are) to another
     * category or subcategory, keeping each product's other categories. With {@code deleteWhenEmpty} the emptied category is
     * deleted afterwards when it has nothing left in it (no subcategories, no products): that is how two categories are merged.
     */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/move-products")
    @Transactional
    public MoveProductsResult moveProducts(@PathVariable UUID id, @RequestBody MoveProductsRequest request) {
        CategoryService.MovedEverything result =
                categoryService.moveEverything(id, request.toCategoryId(), request.deleteWhenEmpty());
        return new MoveProductsResult(result.counts().moved(), result.counts().alreadyThere(), result.deleted(), result.note());
    }

    /**
     * Puts this category under another top-level category, or at the top level when {@code parentId} is null. Two levels
     * only: a category that has subcategories cannot go under another one. Its products stay filed under it.
     */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/move")
    @Transactional
    public CategoryDto move(@PathVariable UUID id, @RequestBody MoveCategoryRequest request) {
        return CategoryDto.from(categoryService.reparent(id, request.parentId()));
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException("A category needs a name");
        if (name.length() > 150) throw new IllegalArgumentException("That name is too long (150 characters at most)");
        return name;
    }

    /** Blank clears the picture. Anything else must be an https address that fits the column (a pasted link, an upload's address). */
    private static String cleanImageUrl(String raw) {
        String url = raw.trim();
        if (url.isEmpty()) return null;
        if (url.length() > 500) throw new IllegalArgumentException("That picture address is too long (500 characters at most)");
        if (!url.regionMatches(true, 0, "https://", 0, 8)) throw new IllegalArgumentException("A picture address must start with https://");
        return url;
    }

    /** name-based slug, suffixed -2, -3… when another category already has it. */
    private String uniqueSlug(String name, UUID selfId) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isEmpty()) base = "category";
        String slug = base;
        int n = 2;
        while (true) {
            var existing = categoryRepository.findBySlug(slug);
            if (existing.isEmpty() || existing.get().getId().equals(selfId)) return slug;
            slug = base + "-" + n++;
        }
    }

    public record CreateCategoryRequest(String name, UUID parentId) {}

    public record MoveProductsRequest(UUID toCategoryId, boolean deleteWhenEmpty) {}

    /** {@code moved} products left this category; {@code alreadyThere} of them were already under the destination too. */
    public record MoveProductsResult(int moved, int alreadyThere, boolean deleted, String note) {}

    public record MoveCategoryRequest(UUID parentId) {}

    public record UpdateCategoryRequest(String name, Integer sortOrder, String imageUrl, Boolean isPublished) {}
}
