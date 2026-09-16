package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.BrandDto;
import com.nbh.backend.catalog.entity.Brand;
import com.nbh.backend.catalog.repository.BrandRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/brands")
public class BrandController {

    private final BrandRepository brandRepository;

    public BrandController(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<BrandDto> list() {
        return brandRepository.findAll().stream().map(BrandDto::from).toList();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping
    @Transactional
    public BrandDto create(@RequestBody CreateBrandRequest request) {
        Brand brand = new Brand();
        brand.setName(request.name());
        brand.setSlug(request.name().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        return BrandDto.from(brandRepository.save(brand));
    }

    public record CreateBrandRequest(String name) {}
}
