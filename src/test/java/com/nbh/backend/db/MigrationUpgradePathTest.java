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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every other test migrates an EMPTY database from V1, which cannot catch a migration that only fails
 * on data an existing deployment already has. This one does what a deploy does: brings a database to
 * V28 (what production ran before the inventory/checkout/TumaBoda work), fills it with rows shaped like
 * real legacy data, and only then applies everything after it.
 *
 * <p>It exists because V33 once renamed VEHICLE orders to MATATU while V14's CHECK still forbade MATATU:
 * fine on any empty database, fatal on one holding a single VEHICLE order — Flyway aborts and the app
 * never boots. Add a legacy-shaped row here whenever a migration rewrites or tightens existing data.
 *
 * <p>Runs in its own scratch schema of the test database, so it cannot disturb the Spring tests' schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MigrationUpgradePathTest {

    private static final String SCHEMA = "upgrade_path_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final UUID vehicleOrder = UUID.randomUUID();
    private final UUID bodaOrder = UUID.randomUUID();
    private final UUID pickupOrder = UUID.randomUUID();
    private final UUID legacyStaff = UUID.randomUUID();
    private final UUID legacyProduct = UUID.randomUUID();
    private final UUID legacyVariant = UUID.randomUUID();
    private final UUID legacyUploadedImage = UUID.randomUUID();
    private final UUID legacyCameraImage = UUID.randomUUID();
    private final UUID legacyCart = UUID.randomUUID();
    private final UUID legacyCartItem = UUID.randomUUID();
    private final UUID legacyOrderItem = UUID.randomUUID();
    private final UUID legacySettings = UUID.randomUUID();

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
    void migrateToProductionsVersionAndSeedLegacyData() throws Exception {
        dropSchema();
        flyway("28").migrate();

        try (Connection c = connect(); Statement s = c.createStatement()) {
            // The checkout's old "vehicle / matatu" choice, stored as VEHICLE until V33 renamed it.
            insertOrder(s, vehicleOrder, "SO-LEGACY-VEHICLE", "DELIVERY_COURIER", "VEHICLE");
            insertOrder(s, bodaOrder, "SO-LEGACY-BODA", "DELIVERY_COURIER", "BODA");
            insertOrder(s, pickupOrder, "SO-LEGACY-PICKUP", "PICKUP", null);
            // A staff login that existed long before stock alerts (V43) — it must not silently drop off the list.
            s.execute("INSERT INTO staff_user (id, email, password, first_name, last_name) VALUES ('" + legacyStaff
                    + "', 'legacy-staff@example.com', 'x', 'Legacy', 'Staffer')");
            seedCatalogueBasketAndPaymentRowsThatPredateV44(s);
        }

        flyway("latest").migrate();
    }

    /**
     * What a live shop already holds when V44-V53 arrive: a sellable product with photos, a basket with a line in
     * it, an order line, and the payment-details row the owner filled in (with the OLD support number V53
     * replaces). V44 adds columns to the variant, cart-line and order-line tables, V45 to the order line, V46 and
     * V53 touch the settings row, V51 tightens a rule on photos that already exist.
     */
    private void seedCatalogueBasketAndPaymentRowsThatPredateV44(Statement s) throws SQLException {
        s.execute("INSERT INTO product (id, slug, name) VALUES ('" + legacyProduct + "', 'legacy-gel-polish', 'Legacy Gel Polish')");
        s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active)"
                + " VALUES ('" + legacyVariant + "', '" + legacyProduct + "', 'LEGACY-001', '15ml', 500, true)");
        // Photos uploaded under the two sources that existed before AI_CLEANED (V51 widens that rule).
        s.execute("INSERT INTO product_image (id, product_id, url, source, is_primary) VALUES ('" + legacyUploadedImage
                + "', '" + legacyProduct + "', 'https://res.cloudinary.com/demo/image/upload/legacy-upload.jpg', 'UPLOAD', true)");
        s.execute("INSERT INTO product_image (id, product_id, url, source, is_primary) VALUES ('" + legacyCameraImage
                + "', '" + legacyProduct + "', 'https://res.cloudinary.com/demo/image/upload/legacy-camera.jpg', 'CAMERA_CAPTURE', false)");
        s.execute("INSERT INTO cart (id, session_id) VALUES ('" + legacyCart + "', 'legacy-session')");
        s.execute("INSERT INTO cart_item (id, cart_id, variant_id, qty, unit_price_snapshot, product_name_snapshot, variant_name_snapshot)"
                + " VALUES ('" + legacyCartItem + "', '" + legacyCart + "', '" + legacyVariant + "', 2, 500, 'Legacy Gel Polish', '15ml')");
        s.execute("INSERT INTO customer_order_item (id, order_id, variant_id, qty, unit_price, vat_rate, line_total, unit_cost_at_sale)"
                + " VALUES ('" + legacyOrderItem + "', '" + vehicleOrder + "', '" + legacyVariant + "', 2, 500, 0, 1000, 300)");
        // The owner's payment details, with the support number V53 replaces.
        s.execute("INSERT INTO payment_settings (id, bank_name, paybill_number, account_name, support_whatsapp)"
                + " VALUES ('" + legacySettings + "', 'Co-operative Bank', '400200', 'Trendysh Accosmetic', '0724808526')");
    }

    @AfterAll
    void cleanUp() throws Exception {
        dropSchema();
    }

    private static void insertOrder(Statement s, UUID id, String orderNo, String fulfilment, String method) throws SQLException {
        s.execute("INSERT INTO customer_order (id, order_no, guest_email, guest_phone, fulfilment_type, status, subtotal,"
                + " vat_total, grand_total, delivery_address, customer_name, delivery_method) VALUES ('" + id + "', '" + orderNo
                + "', 'legacy@example.com', '254711222333', '" + fulfilment + "', 'PAID', 1000, 0, 1000, 'Kilimani', 'Legacy Customer', "
                + (method == null ? "NULL" : "'" + method + "'") + ")");
    }

    private String deliveryMethodOf(UUID orderId) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT delivery_method FROM customer_order WHERE id = '" + orderId + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void everyMigrationAppliedOnTopOfLegacyData() {
        var info = flyway("latest").info();

        assertThat(info.pending()).as("nothing left unapplied").isEmpty();
        assertThat(info.current()).isNotNull();
        assertThat(info.current().getVersion().getVersion()).isNotEqualTo("28");
    }

    @Test
    void legacyVehicleOrdersBecameMatatuAndTheOthersWereLeftAlone() throws Exception {
        assertThat(deliveryMethodOf(vehicleOrder)).isEqualTo("MATATU");
        assertThat(deliveryMethodOf(bodaOrder)).isEqualTo("BODA");
        assertThat(deliveryMethodOf(pickupOrder)).isNull();
    }

    @Test
    void theDeliveryMethodRuleNowAllowsTheThreeCurrentValuesAndNotTheOldOne() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String method : new String[]{"BODA", "MATATU", "COURIER"}) {
                s.execute("UPDATE customer_order SET delivery_method = '" + method + "' WHERE id = '" + bodaOrder + "'");
            }
            assertThatThrownBy(() -> s.execute("UPDATE customer_order SET delivery_method = 'VEHICLE' WHERE id = '" + bodaOrder + "'"))
                    .hasMessageContaining("customer_order_delivery_method_check");
            s.execute("UPDATE customer_order SET delivery_method = 'BODA' WHERE id = '" + bodaOrder + "'");
        }
    }

    @Test
    void aDeliveryFeeIsAllowedOnlyAlongsideATumabodaDropoffPoint() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute("UPDATE customer_order SET delivery_fee = 450 WHERE id = '" + pickupOrder + "'"))
                    .as("V12's guard is narrowed, not gone")
                    .hasMessageContaining("customer_order_delivery_fee_only_for_tumaboda");

            s.execute("UPDATE customer_order SET delivery_fee = 450, dropoff_lat = -1.2921, dropoff_lng = 36.8219 WHERE id = '"
                    + bodaOrder + "'");
            s.execute("UPDATE customer_order SET delivery_fee = 0, dropoff_lat = NULL, dropoff_lng = NULL WHERE id = '" + bodaOrder + "'");
        }
    }

    @Test
    void theCostColumnsKeepTheirWholeNumberDigitsWhenGainingDecimals() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String[] column : new String[][]{
                    {"stock_ledger_entry", "unit_cost"}, {"stock_document_line", "unit_cost"}, {"customer_order_item", "unit_cost_at_sale"}}) {
                try (ResultSet rs = s.executeQuery("SELECT numeric_precision, numeric_scale FROM information_schema.columns"
                        + " WHERE table_schema = '" + SCHEMA + "' AND table_name = '" + column[0] + "' AND column_name = '" + column[1] + "'")) {
                    assertThat(rs.next()).as("%s.%s exists", column[0], column[1]).isTrue();
                    assertThat(rs.getInt(2)).as("%s.%s scale", column[0], column[1]).isEqualTo(4);
                    // Widened from 12 whole digits (14,2): must still hold 12, or a large existing value fails the migration.
                    assertThat(rs.getInt(1) - rs.getInt(2)).as("%s.%s whole digits", column[0], column[1]).isGreaterThanOrEqualTo(12);
                }
            }
        }
    }

    private int count(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    @Test
    void existingOrdersAndCategoriesAreNotTreatedAsDemoData() throws Exception {
        // V42: rows that already existed must come out as real data, or the demo toggle could hide them.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM customer_order")).as("legacy orders survived").isGreaterThanOrEqualTo(3);
            assertThat(count(s, "SELECT count(*) FROM customer_order WHERE is_mock_data")).isZero();
            assertThat(count(s, "SELECT count(*) FROM category")).as("launch categories survived").isGreaterThan(0);
            assertThat(count(s, "SELECT count(*) FROM category WHERE is_mock_data")).isZero();
            // Demo products can no longer be bought, so a migration that flagged an existing product as demo would
            // quietly close that part of the real shop.
            assertThat(count(s, "SELECT count(*) FROM product WHERE id = '" + legacyProduct + "' AND NOT is_mock_data"))
                    .as("the legacy product survived and is a real product").isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM product WHERE is_mock_data")).isZero();
            for (String table : new String[]{"product", "category", "profession", "customer_order"}) {
                assertThat(count(s, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA
                        + "' AND table_name = '" + table + "' AND column_name = 'is_mock_data' AND is_nullable = 'NO'"))
                        .as("%s.is_mock_data exists and is NOT NULL", table).isEqualTo(1);
            }
        }
    }

    @Test
    void existingStaffStartOnTheStockAlertListAndTheSettingsRowHasSaneDefaults() throws Exception {
        // V43: everyone is on the alert list by default, including accounts created before it existed.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM staff_user WHERE id = '" + legacyStaff + "' AND receives_stock_alerts")).isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM staff_user WHERE NOT receives_stock_alerts")).isZero();

            assertThat(count(s, "SELECT count(*) FROM stock_alert_settings")).as("exactly one settings row").isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM stock_alert_settings WHERE lead_time_days = 7 AND safety_days = 3 AND digest_enabled"))
                    .isEqualTo(1);
            assertThatThrownBy(() -> s.execute("UPDATE stock_alert_settings SET lead_time_days = 500"))
                    .hasMessageContaining("lead_time_days");
        }
    }

    private String text(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    private int notNullColumns(Statement s, String table, String... columns) throws SQLException {
        return count(s, "SELECT count(*) FROM information_schema.columns WHERE table_schema = '" + SCHEMA + "' AND table_name = '"
                + table + "' AND is_nullable = 'NO' AND column_name IN ('" + String.join("','", columns) + "')");
    }

    // ---- V44-V53: what happened to the rows a live shop already held ----

    @Test
    void v44_existingVariantsHaveNoWholesaleTierAndExistingBasketAndOrderLinesAreNotWholesale() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM product_variant WHERE id = '" + legacyVariant + "' AND wholesale_min_qty IS NULL"
                    + " AND wholesale_price IS NULL AND selling_price = 500 AND is_active")).as("the variant came through unchanged").isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM cart_item WHERE id = '" + legacyCartItem + "' AND NOT wholesale_applied AND qty = 2"))
                    .isEqualTo(1);
            assertThat(count(s, "SELECT count(*) FROM customer_order_item WHERE id = '" + legacyOrderItem + "' AND NOT wholesale_applied"))
                    .isEqualTo(1);
            assertThat(notNullColumns(s, "cart_item", "wholesale_applied")).isEqualTo(1);
            assertThat(notNullColumns(s, "customer_order_item", "wholesale_applied")).isEqualTo(1);
            // The "both or neither" rule lives in the service, so the database accepts a tier on an existing variant.
            s.execute("UPDATE product_variant SET wholesale_min_qty = 6, wholesale_price = 400 WHERE id = '" + legacyVariant + "'");
            s.execute("UPDATE product_variant SET wholesale_min_qty = NULL, wholesale_price = NULL WHERE id = '" + legacyVariant + "'");
        }
    }

    @Test
    void v45_existingOrderLinesStartWithNothingReturned() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM customer_order_item WHERE id = '" + legacyOrderItem + "' AND returned_qty = 0"))
                    .isEqualTo(1);
            assertThat(notNullColumns(s, "customer_order_item", "returned_qty")).isEqualTo(1);
        }
    }

    @Test
    void v46_coopBankIsOffForTheExistingPaymentSettingsRow() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM payment_settings WHERE id = '" + legacySettings + "' AND NOT coop_bank_enabled"))
                    .isEqualTo(1);
            assertThat(notNullColumns(s, "payment_settings", "coop_bank_enabled")).isEqualTo(1);
        }
    }

    @Test
    void v47_v48_v49_existingOrdersHaveNoDeliveryContactSettlementOrPickupCode() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM customer_order WHERE delivery_contact_phone IS NOT NULL"
                    + " OR tumaboda_settlement_id IS NOT NULL OR tumaboda_pickup_otp IS NOT NULL"))
                    .as("legacy orders keep a NULL for all three new optional fields").isZero();

            // The settlement ledger is usable on top of existing orders: a settlement can sweep one up, and the link is a real foreign key.
            UUID settlement = UUID.randomUUID();
            s.execute("INSERT INTO tumaboda_settlement (id, settled_at, amount, reference) VALUES ('" + settlement
                    + "', DATE '2026-10-01', 450.00, 'BANK-REF-1')");
            try {
                s.execute("UPDATE customer_order SET tumaboda_settlement_id = '" + settlement + "' WHERE id = '" + bodaOrder + "'");
                assertThatThrownBy(() -> s.execute("UPDATE customer_order SET tumaboda_settlement_id = '" + UUID.randomUUID()
                        + "' WHERE id = '" + bodaOrder + "'")).hasMessageContaining("tumaboda_settlement_id");
            } finally {
                s.execute("UPDATE customer_order SET tumaboda_settlement_id = NULL WHERE id = '" + bodaOrder + "'");
                s.execute("DELETE FROM tumaboda_settlement WHERE id = '" + settlement + "'");
            }
            s.execute("UPDATE customer_order SET delivery_contact_phone = '254799888777', tumaboda_pickup_otp = '4821' WHERE id = '" + bodaOrder + "'");
            s.execute("UPDATE customer_order SET delivery_contact_phone = NULL, tumaboda_pickup_otp = NULL WHERE id = '" + bodaOrder + "'");
        }
    }

    @Test
    void v50_deliveryPlacesEnforceTheirRules() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            UUID place = UUID.randomUUID();
            UUID suggestion = UUID.randomUUID();
            s.execute("INSERT INTO delivery_place (id, name) VALUES ('" + place + "', 'Kilimani')");
            try {
                assertThatThrownBy(() -> s.execute("INSERT INTO delivery_place (id, name) VALUES ('" + UUID.randomUUID() + "', 'KILIMANI')"))
                        .as("place names are unique ignoring case").hasMessageContaining("uq_delivery_place_name");

                s.execute("INSERT INTO delivery_place_price (place_id, method, price_min, price_max) VALUES ('" + place + "', 'METRO', 100, 200)");
                assertThatThrownBy(() -> s.execute("INSERT INTO delivery_place_price (place_id, method, price_min, price_max)"
                        + " VALUES ('" + place + "', 'METRO', 100, 200)")).hasMessageContaining("uq_place_method");
                assertThatThrownBy(() -> s.execute("INSERT INTO delivery_place_price (place_id, method, price_min, price_max)"
                        + " VALUES ('" + place + "', 'BODA', 300, 200)")).hasMessageContaining("ck_price_range");

                s.execute("INSERT INTO delivery_place_suggestion (id, name, place_id) VALUES ('" + suggestion + "', 'Kileleshwa', '" + place + "')");
                assertThat(text(s, "SELECT status FROM delivery_place_suggestion WHERE id = '" + suggestion + "'")).isEqualTo("PENDING");
            } finally {
                s.execute("DELETE FROM delivery_place WHERE id = '" + place + "'");
            }
            assertThat(count(s, "SELECT count(*) FROM delivery_place_price WHERE place_id = '" + place + "'"))
                    .as("prices go with their place").isZero();
            assertThat(count(s, "SELECT count(*) FROM delivery_place_suggestion WHERE id = '" + suggestion + "' AND place_id IS NULL"))
                    .as("a suggestion outlives the place it was linked to").isEqualTo(1);
            s.execute("DELETE FROM delivery_place_suggestion WHERE id = '" + suggestion + "'");
        }
    }

    @Test
    void v51_existingPhotosSurviveTheWiderSourceRuleAndAiCleanedIsNowAllowed() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM product_image WHERE id IN ('" + legacyUploadedImage + "', '" + legacyCameraImage
                    + "') AND source IN ('UPLOAD', 'CAMERA_CAPTURE')")).as("both legacy photos kept their source").isEqualTo(2);

            s.execute("UPDATE product_image SET source = 'AI_CLEANED' WHERE id = '" + legacyCameraImage + "'");
            s.execute("UPDATE product_image SET source = 'CAMERA_CAPTURE' WHERE id = '" + legacyCameraImage + "'");
            assertThatThrownBy(() -> s.execute("UPDATE product_image SET source = 'BOGUS' WHERE id = '" + legacyCameraImage + "'"))
                    .as("the rule is widened, not removed").hasMessageContaining("product_image_source_check");

            UUID batch = UUID.randomUUID();
            UUID item = UUID.randomUUID();
            s.execute("INSERT INTO image_cleanup_batch (id, status, images_per_product) VALUES ('" + batch + "', 'IN_PROGRESS', 3)");
            try {
                s.execute("INSERT INTO image_cleanup_item (id, batch_id, product_id, product_image_id, reference_image_id)"
                        + " VALUES ('" + item + "', '" + batch + "', '" + legacyProduct + "', '" + legacyUploadedImage + "', '" + legacyUploadedImage + "')");
                assertThatThrownBy(() -> s.execute("INSERT INTO image_cleanup_item (id, batch_id, product_id, product_image_id, reference_image_id)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + batch + "', '" + UUID.randomUUID() + "', '" + legacyUploadedImage
                        + "', '" + legacyUploadedImage + "')")).as("an item must point at a real product").hasMessageContaining("product_id");
                assertThatThrownBy(() -> s.execute("INSERT INTO image_cleanup_item (id, batch_id, product_id, product_image_id, reference_image_id)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '" + legacyProduct + "', '" + legacyUploadedImage
                        + "', '" + legacyUploadedImage + "')")).as("and belong to a real batch").hasMessageContaining("batch_id");
            } finally {
                s.execute("DELETE FROM image_cleanup_item WHERE batch_id = '" + batch + "'");
                s.execute("DELETE FROM image_cleanup_batch WHERE id = '" + batch + "'");
            }
        }
    }

    @Test
    void v52_integrationSettingsStartEmptyAndEachKeyIsStoredOnce() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM integration_setting")).as("no credentials appear from nowhere").isZero();
            s.execute("INSERT INTO integration_setting (setting_key, value_encrypted) VALUES ('GEMINI_API_KEY', 'ciphertext')");
            try {
                assertThatThrownBy(() -> s.execute("INSERT INTO integration_setting (setting_key, value_encrypted) VALUES ('GEMINI_API_KEY', 'other')"))
                        .hasMessageContaining("integration_setting_pkey");
            } finally {
                s.execute("DELETE FROM integration_setting WHERE setting_key = 'GEMINI_API_KEY'");
            }
        }
    }

    @Test
    void v53_theSupportLineOnTheExistingSettingsRowWasReplacedAndNothingElseOnItTouched() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM payment_settings")).as("still exactly the one row").isEqualTo(1);
            assertThat(text(s, "SELECT support_whatsapp FROM payment_settings WHERE id = '" + legacySettings + "'")).isEqualTo("0793506900");
            assertThat(text(s, "SELECT bank_name FROM payment_settings WHERE id = '" + legacySettings + "'"))
                    .as("the owner's bank details are not part of this change").isEqualTo("Co-operative Bank");
            assertThat(text(s, "SELECT paybill_number FROM payment_settings WHERE id = '" + legacySettings + "'")).isEqualTo("400200");
        }
    }

    @Test
    void v54_everyExistingOrderIsMarkedAsAlreadyRemindedSoDeployingNeverEmailsABacklogOfOldOrders() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM customer_order WHERE payment_reminder_sent_at IS NULL"))
                    .as("no legacy order is left waiting for a reminder").isZero();
            assertThat(count(s, "SELECT count(*) FROM customer_order WHERE payment_reminder_sent_at IS NOT NULL"))
                    .as("the legacy orders are the ones marked").isGreaterThanOrEqualTo(3);

            // An order placed after the migration starts unreminded, which is what makes it eligible for its one reminder.
            UUID fresh = UUID.randomUUID();
            insertOrder(s, fresh, "SO-AFTER-V54", "PICKUP", null);
            try {
                assertThat(count(s, "SELECT count(*) FROM customer_order WHERE id = '" + fresh + "' AND payment_reminder_sent_at IS NULL"))
                        .isEqualTo(1);
            } finally {
                s.execute("DELETE FROM customer_order WHERE id = '" + fresh + "'");
            }
        }
    }

    @Test
    void v55_legacyOrdersHoldNoDeliveryDetailsAndTheTimeWindowIsLimitedToTheFourChoices() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThat(count(s, "SELECT count(*) FROM customer_order WHERE delivery_estate IS NOT NULL OR delivery_street IS NOT NULL"
                    + " OR delivery_building IS NOT NULL OR delivery_unit IS NOT NULL OR delivery_courier_office IS NOT NULL"
                    + " OR delivery_directions IS NOT NULL OR delivery_instructions IS NOT NULL"
                    + " OR delivery_receiver_name IS NOT NULL OR delivery_time_window IS NOT NULL"))
                    .as("nothing is backfilled onto an order that pre-dates the details").isZero();

            UUID fresh = UUID.randomUUID();
            insertOrder(s, fresh, "SO-AFTER-V55", "DELIVERY_COURIER", "BODA");
            try {
                s.execute("UPDATE customer_order SET delivery_building = 'Yaya Towers', delivery_unit = '4th floor',"
                        + " delivery_time_window = 'ANY_TIME' WHERE id = '" + fresh + "'");
                assertThat(text(s, "SELECT delivery_building FROM customer_order WHERE id = '" + fresh + "'")).isEqualTo("Yaya Towers");
                assertThatThrownBy(() -> s.execute("UPDATE customer_order SET delivery_time_window = 'MIDNIGHT' WHERE id = '" + fresh + "'"))
                        .as("only the four choices").hasMessageContaining("customer_order_delivery_time_window_check");
            } finally {
                s.execute("DELETE FROM customer_order WHERE id = '" + fresh + "'");
            }
        }
    }

    @Test
    void theNewVersionColumnsStartAtZeroForRowsThatAlreadyExisted() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM stock_balance WHERE version <> 0")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
