package com.nbh.backend.order.entity;

import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An order line answers "what was bought" from what it froze when the order was placed, and only an order that froze
 * nothing (placed before V62) falls back to the live variant. Pure logic, so no Spring and no database.
 */
class CustomerOrderItemSnapshotUnitTest {

    private static ProductVariant variant(String productName, String sku, String variantName) {
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName(productName);
        ProductVariant variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setProduct(product);
        variant.setSku(sku);
        variant.setVariantName(variantName);
        return variant;
    }

    private static CustomerOrderItem lineFor(ProductVariant variant) {
        CustomerOrderItem item = new CustomerOrderItem();
        item.setVariant(variant);
        return item;
    }

    @Test
    void aFrozenLineKeepsAnsweringWithWhatItFrozenWhateverHappensToTheVariantAfterwards() {
        ProductVariant live = variant("Velvet Matte Lipstick", "VML-RR-50", "Velvet Matte Lipstick – Ruby Red / 50ml");
        live.setOptionLabel("Ruby Red / 50ml");
        live.setOptionColor("Ruby Red");
        live.setOptionSize("50ml");
        live.setSwatchHex("#9B111E");
        CustomerOrderItem item = lineFor(live);
        item.rememberVariant(live, "https://res.cloudinary.com/demo/image/upload/ruby.jpg");

        live.getProduct().setName("Renamed product");
        live.setSku("OTHER-SKU");
        live.setVariantName("Something else entirely");
        live.setOptionLabel("Other / 1l");
        live.setOptionColor("Other");
        live.setOptionSize("1l");
        live.setSwatchHex("#000000");

        assertThat(item.hasSnapshot()).isTrue();
        assertThat(item.soldSku()).isEqualTo("VML-RR-50");
        assertThat(item.soldProductName()).isEqualTo("Velvet Matte Lipstick");
        assertThat(item.soldVariantName()).isEqualTo("Velvet Matte Lipstick – Ruby Red / 50ml");
        assertThat(item.soldOptionLabel()).isEqualTo("Ruby Red / 50ml");
        assertThat(item.soldOptionColor()).isEqualTo("Ruby Red");
        assertThat(item.soldOptionSize()).isEqualTo("50ml");
        assertThat(item.soldSwatchHex()).isEqualTo("#9B111E");
        assertThat(item.soldImageUrl()).isEqualTo("https://res.cloudinary.com/demo/image/upload/ruby.jpg");
        assertThat(item.soldLabel()).isEqualTo("Velvet Matte Lipstick (Ruby Red / 50ml)");
    }

    @Test
    void aLineFromBeforeSnapshotsExistedAnswersFromTheLiveVariant() {
        ProductVariant live = variant("Gel Polish", "GEL-1", "Gel Polish – Ruby Red");
        live.setOptionColor("Ruby Red");
        live.setSwatchHex("#9B111E");
        CustomerOrderItem legacy = lineFor(live); // nothing frozen: a row from before V62

        assertThat(legacy.hasSnapshot()).isFalse();
        assertThat(legacy.soldSku()).isEqualTo("GEL-1");
        assertThat(legacy.soldProductName()).isEqualTo("Gel Polish");
        assertThat(legacy.soldVariantName()).isEqualTo("Gel Polish – Ruby Red");
        assertThat(legacy.soldOptionLabel()).as("derived the way the shop always did").isEqualTo("Ruby Red");
        assertThat(legacy.soldOptionColor()).isEqualTo("Ruby Red");
        assertThat(legacy.soldSwatchHex()).isEqualTo("#9B111E");
        assertThat(legacy.soldImageUrl()).as("nothing is looked up live for a photo").isNull();
        assertThat(legacy.soldLabel()).isEqualTo("Gel Polish (Ruby Red)");

        // ...and it follows the variant, which is exactly the old behaviour this fallback keeps for old orders.
        live.setSku("GEL-2");
        live.getProduct().setName("Gel Polish Pro");
        assertThat(legacy.soldSku()).isEqualTo("GEL-2");
        assertThat(legacy.soldProductName()).isEqualTo("Gel Polish Pro");
    }

    @Test
    void theOptionIsWorkedOutFromTheFrozenNamesSoRenamingTheProductNeverChangesIt() {
        ProductVariant live = variant("Gel Polish", "GEL-1", "Gel Polish – Ruby Red");
        CustomerOrderItem item = lineFor(live);
        item.rememberVariant(live, null);
        assertThat(item.soldOptionLabel()).isEqualTo("Ruby Red");

        // Today's product title no longer prefixes the variant name, so re-deriving from live values would say
        // "Gel Polish – Ruby Red"; the line derives from the frozen pair and still says "Ruby Red".
        live.getProduct().setName("Lacquer");
        assertThat(item.soldOptionLabel()).isEqualTo("Ruby Red");
        assertThat(item.soldLabel()).isEqualTo("Gel Polish (Ruby Red)");
    }

    @Test
    void aProductWithOneUnnamedOptionIsJustItsName() {
        for (String meaningless : new String[]{"Default", "default", "", "   "}) {
            ProductVariant live = variant("Cuticle Oil", "OIL-1", meaningless);
            CustomerOrderItem item = lineFor(live);
            item.rememberVariant(live, null);
            assertThat(item.soldOptionLabel()).as("'%s'", meaningless).isNull();
            assertThat(item.soldLabel()).as("'%s'", meaningless).isEqualTo("Cuticle Oil");
        }
    }

    @Test
    void aVariantWithNoColourSizeOrDotFreezesNullsNotPlaceholders() {
        ProductVariant live = variant("Nail File", "FILE-1", "Default");
        CustomerOrderItem item = lineFor(live);
        item.rememberVariant(live, null);

        assertThat(item.getSkuSnapshot()).isEqualTo("FILE-1");
        assertThat(item.soldOptionColor()).isNull();
        assertThat(item.soldOptionSize()).isNull();
        assertThat(item.soldSwatchHex()).isNull();
        assertThat(item.soldImageUrl()).isNull();
    }
}
