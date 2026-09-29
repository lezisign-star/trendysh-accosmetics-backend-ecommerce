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
        }

        flyway("latest").migrate();
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

    @Test
    void theNewVersionColumnsStartAtZeroForRowsThatAlreadyExisted() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM stock_balance WHERE version <> 0")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }
}
