package com.nbh.backend.kits.controller;

import com.nbh.backend.kits.dto.KitPublicDto;
import com.nbh.backend.kits.service.KitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The "Shop by profession" modal's data source — no authentication, mirrors
 * {@code CatalogController}'s separation from staff-only catalogue management.
 */
@RestController
@RequestMapping("/api/v1/catalog/kits")
public class KitPublicController {

    private final KitService service;

    public KitPublicController(KitService service) {
        this.service = service;
    }

    @GetMapping
    public List<KitPublicDto> list() {
        return service.listPublicKits();
    }
}
