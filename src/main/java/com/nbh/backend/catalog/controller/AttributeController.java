package com.nbh.backend.catalog.controller;

import com.nbh.backend.catalog.dto.ProductAttributeDto;
import com.nbh.backend.catalog.entity.ProductAttribute;
import com.nbh.backend.catalog.entity.ProductAttributeValue;
import com.nbh.backend.catalog.repository.ProductAttributeRepository;
import com.nbh.backend.catalog.repository.ProductAttributeValueRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Staff-only metadata for the variant builder — the "Shade: Red/Blue/Green,
 * Size: 30ml/50ml" picklist a product's variants are built from. Not part of
 * the public catalogue API: a customer never needs the raw attribute list,
 * only the resolved values a specific variant already has (see
 * {@code CatalogVariantDto}).
 */
@RestController
@RequestMapping("/api/v1/attributes")
public class AttributeController {

    private final ProductAttributeRepository attributeRepository;
    private final ProductAttributeValueRepository valueRepository;

    public AttributeController(ProductAttributeRepository attributeRepository,
                                ProductAttributeValueRepository valueRepository) {
        this.attributeRepository = attributeRepository;
        this.valueRepository = valueRepository;
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_VIEW') or hasAuthority('PERM_PRODUCT_MANAGE')")
    @GetMapping
    @Transactional(readOnly = true)
    public List<ProductAttributeDto> list() {
        List<ProductAttribute> attributes = attributeRepository.findAllByOrderBySortOrderAsc();
        List<UUID> ids = attributes.stream().map(ProductAttribute::getId).toList();
        Map<UUID, List<ProductAttributeValue>> valuesByAttribute = ids.isEmpty()
                ? Map.of()
                : valueRepository.findByAttributeIdInOrderBySortOrderAsc(ids).stream()
                        .collect(Collectors.groupingBy(v -> v.getAttribute().getId()));

        return attributes.stream()
                .map(a -> ProductAttributeDto.from(a, valuesByAttribute.getOrDefault(a.getId(), List.of())))
                .toList();
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping
    @Transactional
    public ProductAttributeDto create(@Valid @RequestBody CreateAttributeRequest request) {
        if (attributeRepository.existsByCode(request.code())) {
            throw new ConflictException("An attribute with code '" + request.code() + "' already exists");
        }
        ProductAttribute attribute = new ProductAttribute();
        attribute.setCode(request.code());
        attribute.setName(request.name());
        attribute.setDisplayType(request.displayType());
        return ProductAttributeDto.from(attributeRepository.save(attribute), List.of());
    }

    @PreAuthorize("hasAuthority('PERM_PRODUCT_MANAGE')")
    @PostMapping("/{id}/values")
    @Transactional
    public ProductAttributeDto.AttributeValueDto addValue(@PathVariable UUID id,
                                                            @Valid @RequestBody AddAttributeValueRequest request) {
        ProductAttribute attribute = attributeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("No attribute: " + id));

        ProductAttributeValue value = new ProductAttributeValue();
        value.setAttribute(attribute);
        value.setValue(request.value());
        value.setDisplayValue(request.displayValue());
        value.setSwatchHex(request.swatchHex());
        return ProductAttributeDto.AttributeValueDto.from(valueRepository.save(value));
    }

    public record CreateAttributeRequest(
            @NotBlank String code,
            @NotBlank String name,
            ProductAttribute.AttributeDisplayType displayType
    ) {}

    public record AddAttributeValueRequest(@NotBlank String value, String displayValue, String swatchHex) {}
}
