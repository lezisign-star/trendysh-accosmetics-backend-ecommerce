package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.CategoryDto;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.common.exception.ConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    public CategoryController(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<CategoryDto> list() {
        Map<UUID, Long> direct = new HashMap<>();
        for (Object[] row : categoryRepository.liveProductCountsByCategory()) {
            direct.merge((UUID) row[0], (Long) row[1], Long::sum);
        }
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
                .map(c -> CategoryDto.from(c, rolledUp.getOrDefault(c.getId(), 0L)))
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
        refuseSameNameBeside(name, parent, null);
        Category category = new Category();
        category.setName(name);
        category.setSlug(uniqueSlug(name, null));
        category.setParent(parent);
        return CategoryDto.from(categoryRepository.save(category));
    }

    /** Partial update: only non-null fields change. Re-parenting is deliberately not supported. */
    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PatchMapping("/{id}")
    @Transactional
    public CategoryDto update(@PathVariable UUID id, @RequestBody UpdateCategoryRequest request) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No category: " + id));
        if (request.name() != null) {
            String name = cleanName(request.name());
            if (!name.equals(category.getName())) {
                refuseSameNameBeside(name, category.getParent(), category.getId());
                category.setName(name);
                category.setSlug(uniqueSlug(name, category.getId()));
            }
        }
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());
        if (request.imageUrl() != null) category.setImageUrl(request.imageUrl().isBlank() ? null : request.imageUrl().trim());
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
        categoryRepository.delete(category);
    }

    /**
     * Two categories with the same name side by side look identical in the shop's menu and in the product
     * form, and nobody can tell which to use. The same name under a different parent is fine ("Accessories"
     * under Nails and under Lashes).
     */
    private void refuseSameNameBeside(String name, Category parent, UUID selfId) {
        List<Category> same = parent == null
                ? categoryRepository.findByParentIsNullAndNameIgnoreCase(name)
                : categoryRepository.findByParentIdAndNameIgnoreCase(parent.getId(), name);
        if (same.stream().anyMatch(c -> !c.getId().equals(selfId))) {
            throw new ConflictException("There is already a category called \"" + name + "\""
                    + (parent == null ? "" : " under " + parent.getName()) + ".");
        }
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty()) throw new IllegalArgumentException("A category needs a name");
        if (name.length() > 150) throw new IllegalArgumentException("That name is too long (150 characters at most)");
        return name;
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

    public record UpdateCategoryRequest(String name, Integer sortOrder, String imageUrl, Boolean isPublished) {}
}
