package com.nbh.backend.packages.controller;

import com.nbh.backend.packages.dto.PackageDetailDto;
import com.nbh.backend.packages.dto.PackagePublicDto;
import com.nbh.backend.packages.service.PackageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The "Shop by profession" modal's data source — no authentication, mirrors
 * {@code CatalogController}'s separation from staff-only catalogue management.
 */
@RestController
@RequestMapping("/api/v1/catalog/packages")
public class PackagePublicController {

    private final PackageService service;

    public PackagePublicController(PackageService service) {
        this.service = service;
    }

    @GetMapping
    public List<PackagePublicDto> list() {
        return service.listPublicPackages();
    }

    /** One package's own page: its photos, price and, for every product in it, what it is and how it is used. */
    @GetMapping("/{slug}")
    public PackageDetailDto detail(@PathVariable String slug) {
        return service.publicPackage(slug);
    }
}
