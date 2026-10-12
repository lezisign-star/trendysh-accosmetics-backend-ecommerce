package com.nbh.backend.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V62 gives every order line the snapshot columns and fills them for the orders a live shop already holds, from the
 * variant as it is at migration time. This brings a scratch schema to V61 (what production runs before it), seeds order
 * lines shaped like real ones (a generated variant with colour/size/dot and photos, a sibling with no photo of its
 * own, a plain one-option product, one-option next to a deleted sibling), and only then applies V62 and everything
 * after it. Its own schema, so it cannot disturb the Spring tests' schema or the other upgrade-path test's.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderLineSnapshotMigrationTest {

    private static final String SCHEMA = "order_snapshot_upgrade_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");
    private static final String CDN = "https://res.cloudinary.com/demo/image/upload/";

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final UUID order = UUID.randomUUID();

    private final UUID lipstick = UUID.randomUUID();
    private final UUID ruby = UUID.randomUUID();
    private final UUID nude = UUID.randomUUID();
    private final UUID rubyLine = UUID.randomUUID();
    private final UUID nudeLine = UUID.randomUUID();

    private final UUID oil = UUID.randomUUID();
    private final UUID oilOption = UUID.randomUUID();
    private final UUID oilLine = UUID.randomUUID();

    private final UUID polish = UUID.randomUUID();
    private final UUID polishOption = UUID.randomUUID();
    private final UUID polishDeletedSibling = UUID.randomUUID();
    private final UUID polishLine = UUID.randomUUID();

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        try (Statement s = connection.createStatement()) {
            s.execute("SET search_path TO " + SCHEMA);
        }
        return connection;
    }

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(URL, USER, PASSWORD)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private static void dropSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    @BeforeAll
    void migrateToTheVersionBeforeV62SeedOrderLinesThenApplyV62() throws Exception {
        dropSchema();
        flyway("61").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            seed(s);
        }
        flyway("latest").migrate();
    }

    @AfterAll
    void cleanUp() throws Exception {
        dropSchema();
    }

    private void seed(Statement s) throws SQLException {
        s.execute("INSERT INTO customer_order (id, order_no, guest_email, guest_phone, fulfilment_type, status, subtotal,"
                + " vat_total, grand_total, delivery_address, customer_name) VALUES ('" + order + "', 'SO-SNAPSHOT-1',"
                + " 'snapshot@example.com', '254711222333', 'PICKUP', 'PAID', 2500, 0, 2500, 'Kilimani', 'Snapshot Customer')");

        // A product with several options, shaped like what the variant generator writes (V61): colour, size, dot.
        s.execute("INSERT INTO product (id, slug, name) VALUES ('" + lipstick + "', 'velvet-matte-lipstick', 'Velvet Matte Lipstick')");
        variant(s, ruby, lipstick, "VML-RR-50", "Velvet Matte Lipstick – Ruby Red / 50ml", "Ruby Red / 50ml", "Ruby Red", "50ml", "#9B111E", false);
        variant(s, nude, lipstick, "VML-NB-50", "Velvet Matte Lipstick – Nude Beige / 50ml", "Nude Beige / 50ml", "Nude Beige", "50ml", "#D2B48C", false);
        // Ruby's own photos: the main one has the LARGER sort order, so "first in gallery order" would pick the wrong one.
        photo(s, lipstick, ruby, "ruby-second", false, 1);
        photo(s, lipstick, ruby, "ruby-main", true, 5);
        // A photo still filed at product level on a product with several options is unassigned: nobody's cover.
        photo(s, lipstick, null, "shared-unassigned", true, 0);
        line(s, rubyLine, ruby, 2, 650);
        line(s, nudeLine, nude, 1, 650);

        // A plain one-option product (what the product editor saves): no colour, no size, no dot.
        s.execute("INSERT INTO product (id, slug, name) VALUES ('" + oil + "', 'cuticle-oil', 'Cuticle Oil')");
        variant(s, oilOption, oil, "OIL-1", "Default", null, null, null, null, false);
        photo(s, oil, null, "oil-main", true, 0);
        line(s, oilLine, oilOption, 1, 300);

        // One live option next to a deleted sibling is still a single-option product, so its product-level photo counts.
        s.execute("INSERT INTO product (id, slug, name) VALUES ('" + polish + "', 'gel-polish', 'Gel Polish')");
        variant(s, polishOption, polish, "GEL-1", "Gel Polish – Ruby", null, null, null, null, false);
        variant(s, polishDeletedSibling, polish, "GEL-OLD", "Gel Polish – Old Shade", null, null, null, null, true);
        photo(s, polish, null, "polish-main", true, 0);
        line(s, polishLine, polishOption, 1, 600);
    }

    private void variant(Statement s, UUID id, UUID product, String sku, String name, String label, String color,
                         String size, String hex, boolean deleted) throws SQLException {
        s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted,"
                + " option_label, option_color, option_size, swatch_hex) VALUES ('" + id + "', '" + product + "', '" + sku
                + "', '" + name + "', 650, true, " + deleted + ", " + quoted(label) + ", " + quoted(color) + ", "
                + quoted(size) + ", " + quoted(hex) + ")");
    }

    private void photo(Statement s, UUID product, UUID variant, String name, boolean main, int sortOrder) throws SQLException {
        s.execute("INSERT INTO product_image (id, product_id, variant_id, url, source, is_primary, sort_order) VALUES ('"
                + UUID.randomUUID() + "', '" + product + "', " + (variant == null ? "NULL" : "'" + variant + "'") + ", '"
                + CDN + name + ".jpg', 'UPLOAD', " + main + ", " + sortOrder + ")");
    }

    private void line(Statement s, UUID id, UUID variant, int qty, int unitPrice) throws SQLException {
        s.execute("INSERT INTO customer_order_item (id, order_id, variant_id, qty, unit_price, vat_rate, line_total)"
                + " VALUES ('" + id + "', '" + order + "', '" + variant + "', " + qty + ", " + unitPrice + ", 0, "
                + (qty * unitPrice) + ")");
    }

    private static String quoted(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private String column(UUID line, String column) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + column + " FROM customer_order_item WHERE id = '" + line + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void aGeneratedVariantsLineGetsItsNamesSkuColourSizeDotAndItsOwnMainPhoto() throws Exception {
        assertThat(column(rubyLine, "product_name_snapshot")).isEqualTo("Velvet Matte Lipstick");
        assertThat(column(rubyLine, "variant_name_snapshot")).isEqualTo("Velvet Matte Lipstick – Ruby Red / 50ml");
        assertThat(column(rubyLine, "sku_snapshot")).isEqualTo("VML-RR-50");
        assertThat(column(rubyLine, "option_label_snapshot")).isEqualTo("Ruby Red / 50ml");
        assertThat(column(rubyLine, "option_color_snapshot")).isEqualTo("Ruby Red");
        assertThat(column(rubyLine, "option_size_snapshot")).isEqualTo("50ml");
        assertThat(column(rubyLine, "swatch_hex_snapshot")).isEqualTo("#9B111E");
        assertThat(column(rubyLine, "image_url_snapshot")).as("its main photo, not the first by sort order")
                .isEqualTo(CDN + "ruby-main.jpg");
    }

    @Test
    void anOptionWithNoPhotoOfItsOwnNeverBorrowsASiblingsOrAnUnassignedOne() throws Exception {
        assertThat(column(nudeLine, "sku_snapshot")).isEqualTo("VML-NB-50");
        assertThat(column(nudeLine, "option_color_snapshot")).isEqualTo("Nude Beige");
        assertThat(column(nudeLine, "image_url_snapshot")).isNull();
    }

    @Test
    void aPlainOneOptionProductKeepsNullOptionsAndItsProductLevelPhoto() throws Exception {
        assertThat(column(oilLine, "product_name_snapshot")).isEqualTo("Cuticle Oil");
        assertThat(column(oilLine, "variant_name_snapshot")).isEqualTo("Default");
        assertThat(column(oilLine, "sku_snapshot")).isEqualTo("OIL-1");
        assertThat(column(oilLine, "option_label_snapshot")).isNull();
        assertThat(column(oilLine, "option_color_snapshot")).isNull();
        assertThat(column(oilLine, "option_size_snapshot")).isNull();
        assertThat(column(oilLine, "swatch_hex_snapshot")).isNull();
        assertThat(column(oilLine, "image_url_snapshot")).isEqualTo(CDN + "oil-main.jpg");
    }

    @Test
    void aDeletedSiblingDoesNotMakeTheLiveOptionLoseItsProductLevelPhoto() throws Exception {
        assertThat(column(polishLine, "sku_snapshot")).isEqualTo("GEL-1");
        assertThat(column(polishLine, "image_url_snapshot")).isEqualTo(CDN + "polish-main.jpg");
    }

    @Test
    void theMigrationLeavesTheRestOfEveryLineAlone() throws Exception {
        assertThat(column(rubyLine, "qty")).isEqualTo("2.000");
        assertThat(column(rubyLine, "unit_price")).isEqualTo("650.00");
        assertThat(column(rubyLine, "line_total")).isEqualTo("1300.00");
        assertThat(column(rubyLine, "returned_qty")).isEqualTo("0.000");
        assertThat(column(rubyLine, "wholesale_applied::text")).isEqualTo("false");
    }

    @Test
    void aLineWrittenWithoutSnapshotsIsStillAccepted() throws Exception {
        // An older backend instance still running during a rolling deploy inserts lines with none of the new columns.
        UUID stray = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            line(s, stray, nude, 1, 650);
            try {
                assertThat(column(stray, "sku_snapshot")).isNull();
                assertThat(column(stray, "image_url_snapshot")).isNull();
            } finally {
                s.execute("DELETE FROM customer_order_item WHERE id = '" + stray + "'");
            }
        }
    }

    @Test
    void everyMigrationAppliedOnTopOfTheSeededLines() {
        var info = flyway("latest").info();
        assertThat(info.pending()).isEmpty();
        assertThat(info.current().getVersion().getVersion()).isNotEqualTo("61");
    }
}
