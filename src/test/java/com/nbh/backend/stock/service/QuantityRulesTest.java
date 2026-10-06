package com.nbh.backend.stock.service;

import com.nbh.backend.catalog.entity.ProductVariant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Found in the 2026-10-06 go-live QA: a stock count of 2.5 was saved for a lipstick, and a count of a trillion
 * came back as a baffling "A conflicting record already exists" error. A typed-in quantity has to be a count the
 * shop could really hold, in whole units for anything sold by the piece.
 */
class QuantityRulesTest {

    private static ProductVariant variant(String sku, String uom) {
        ProductVariant v = new ProductVariant();
        v.setSku(sku);
        if (uom != null) v.setUom(uom);
        return v;
    }

    @Test
    void piecesAreCountedInWholeUnits() {
        ProductVariant piece = variant("LIP-001", "PIECE");

        assertThatCode(() -> QuantityRules.requireSensible("LIP-001", piece, new BigDecimal("4"))).doesNotThrowAnyException();
        assertThatCode(() -> QuantityRules.requireSensible("LIP-001", piece, new BigDecimal("4.000"))).doesNotThrowAnyException();
        assertThatCode(() -> QuantityRules.requireSensible("LIP-001", piece, BigDecimal.ZERO)).doesNotThrowAnyException();
        assertThatThrownBy(() -> QuantityRules.requireSensible("LIP-001", piece, new BigDecimal("2.5")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIP-001").hasMessageContaining("whole number");
    }

    @Test
    void aVariantWithNoUnitOfMeasureIsTreatedAsPieces() {
        ProductVariant unspecified = variant("X-1", null);
        unspecified.setUom(null);

        assertThatThrownBy(() -> QuantityRules.requireSensible("X-1", unspecified, new BigDecimal("0.5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void somethingSoldByWeightOrLengthMayCarryAFraction() {
        ProductVariant byWeight = variant("RIB-1", "KG");

        assertThatCode(() -> QuantityRules.requireSensible("RIB-1", byWeight, new BigDecimal("2.5"))).doesNotThrowAnyException();
    }

    @Test
    void aNumberBeyondWhatAShelfCouldHoldIsATypoWhateverTheUnit() {
        ProductVariant piece = variant("LIP-001", "PIECE");
        ProductVariant byWeight = variant("RIB-1", "KG");

        assertThatCode(() -> QuantityRules.requireSensible("LIP-001", piece, QuantityRules.MAX_QTY)).doesNotThrowAnyException();
        assertThatThrownBy(() -> QuantityRules.requireSensible("LIP-001", piece, QuantityRules.MAX_QTY.add(BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");
        assertThatThrownBy(() -> QuantityRules.requireSensible("LIP-001", piece, new BigDecimal("1000000000000")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");
        assertThatThrownBy(() -> QuantityRules.requireSensible("RIB-1", byWeight, new BigDecimal("5000000.5")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("too large");
    }

    @Test
    void aMissingQuantityIsLeftForTheCallersOwnRequiredCheck() {
        assertThatCode(() -> QuantityRules.requireSensible("LIP-001", variant("LIP-001", "PIECE"), null)).doesNotThrowAnyException();
    }
}
