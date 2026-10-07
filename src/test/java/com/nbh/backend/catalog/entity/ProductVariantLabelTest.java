package com.nbh.backend.catalog.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How a message names an item: the product, plus the option in brackets only when the option's name says something. */
class ProductVariantLabelTest {

    private static ProductVariant variant(String productName, String variantName) {
        Product product = new Product();
        product.setName(productName);
        ProductVariant variant = new ProductVariant();
        variant.setProduct(product);
        variant.setVariantName(variantName);
        return variant;
    }

    @Test
    void anOptionWithARealNameIsShownInBrackets() {
        assertThat(variant("Gel polish", "Ruby Red").displayLabel()).isEqualTo("Gel polish (Ruby Red)");
    }

    @Test
    void aBlankOrPlaceholderNameLeavesJustTheProductName() {
        for (String nothing : new String[]{"", "  ", "Default", "DEFAULT", null}) {
            assertThat(variant("Gel polish", nothing).displayLabel())
                    .as("option named " + nothing)
                    .isEqualTo("Gel polish");
        }
    }
}
