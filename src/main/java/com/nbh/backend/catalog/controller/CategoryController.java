package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.CategoryDto;
import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.repository.CategoryRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
        return categoryRepository.findAll().stream().map(CategoryDto::from).toList();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping
    @Transactional
    public CategoryDto create(@RequestBody CreateCategoryRequest request) {
        Category category = new Category();
        category.setName(request.name());
        category.setSlug(request.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        if (request.parentId() != null) {
            category.setParent(categoryRepository.findById(request.parentId())
                    .orElseThrow(() -> new EntityNotFoundException("No category: " + request.parentId())));
        }
        return CategoryDto.from(categoryRepository.save(category));
    }

    public record CreateCategoryRequest(String name, UUID parentId) {}
}
