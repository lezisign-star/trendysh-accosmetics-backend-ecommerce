package com.nbh.backend.catalog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Join row: which attribute values (e.g. Shade=04, Size=30ml) define this SKU. */
@Getter
@Setter
@Entity
@Table(name = "variant_attribute_value")
@IdClass(VariantAttributeValue.Id.class)
public class VariantAttributeValue {

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private ProductVariant variant;

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attribute_value_id", nullable = false)
    private ProductAttributeValue attributeValue;

    @Getter
    @Setter
    public static class Id implements Serializable {
        private UUID variant;
        private UUID attributeValue;

        public Id() {}

        public Id(UUID variant, UUID attributeValue) {
            this.variant = variant;
            this.attributeValue = attributeValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return Objects.equals(variant, id.variant) && Objects.equals(attributeValue, id.attributeValue);
        }

        @Override
        public int hashCode() {
            return Objects.hash(variant, attributeValue);
        }
    }
}
