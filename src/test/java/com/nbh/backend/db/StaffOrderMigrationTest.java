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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V70 gives an order's lines a position, a pick-and-pack tick, and records who entered an order; it also lets an order have
 * no email. This brings a scratch schema to V69, seeds two orders shaped like the live ones (one with a kit and its own
 * products, one with plain lines), and only then applies V70 and the rest: every existing line must be numbered in its own
 * order in the order it was stored, nothing is ticked as packed, no order claims to have been entered by staff, and an
 * order without an email can now be stored.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffOrderMigrationTest {

    private static final String SCHEMA = "staff_order_upgrade_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final UUID product = UUID.randomUUID();
    private final UUID polish = UUID.randomUUID();
    private final UUID lamp = UUID.randomUUID();
    private final UUID oil = UUID.randomUUID();
    private final UUID kitVariant = UUID.randomUUID();
    private final UUID kitOrder = UUID.randomUUID();
    private final UUID plainOrder = UUID.randomUUID();
    private final UUID kitLine = UUID.randomUUID();
    private final UUID kitPolishLine = UUID.randomUUID();
    private final UUID kitLampLine = UUID.randomUUID();
    private final UUID kitOilLine = UUID.randomUUID();
    private final UUID plainLine1 = UUID.randomUUID();
    private final UUID plainLine2 = UUID.randomUUID();

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
    void migrateToV69SeedTwoOrdersThenApplyV70() throws Exception {
        dropSchema();
        flyway("69").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO product (id, slug, name) VALUES ('" + product + "', 'starter-things', 'Starter Things')");
            variant(s, polish, "ST-1", false);
            variant(s, lamp, "ST-2", false);
            variant(s, oil, "ST-3", false);
            variant(s, kitVariant, "KIT-1", true);

            order(s, kitOrder, "SO-STAFF-1", 7500);
            // A kit's own line first, then its products (what checkout wrote), in this stored order.
            line(s, kitLine, kitOrder, kitVariant, null, 1, 7500);
            line(s, kitPolishLine, kitOrder, polish, kitLine, 2, 850);
            line(s, kitLampLine, kitOrder, lamp, kitLine, 1, 4500);
            line(s, kitOilLine, kitOrder, oil, kitLine, 1, 450);

            order(s, plainOrder, "SO-STAFF-2", 1300);
            line(s, plainLine1, plainOrder, oil, null, 1, 450);
            line(s, plainLine2, plainOrder, polish, null, 1, 850);
        }
        flyway("latest").migrate();
    }

    @AfterAll
    void cleanUp() throws Exception {
        dropSchema();
    }

    private void variant(Statement s, UUID id, String sku, boolean isKit) throws SQLException {
        s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted, is_kit)"
                + " VALUES ('" + id + "', '" + product + "', '" + sku + "', 'Default', 500, true, false, " + isKit + ")");
    }

    private void order(Statement s, UUID id, String orderNo, int total) throws SQLException {
        s.execute("INSERT INTO customer_order (id, order_no, guest_email, guest_phone, fulfilment_type, status, subtotal,"
                + " vat_total, grand_total, delivery_address, customer_name) VALUES ('" + id + "', '" + orderNo
                + "', 'legacy@example.com', '254711222333', 'PICKUP', 'PAID', " + total + ", 0, " + total
                + ", 'Kilimani', 'Legacy Customer')");
    }

    private void line(Statement s, UUID id, UUID order, UUID variant, UUID parent, int qty, int unitPrice) throws SQLException {
        s.execute("INSERT INTO customer_order_item (id, order_id, variant_id, qty, unit_price, vat_rate, line_total,"
                + " parent_item_id) VALUES ('" + id + "', '" + order + "', '" + variant + "', " + qty + ", " + unitPrice
                + ", 0, " + (qty * unitPrice) + ", " + (parent == null ? "NULL" : "'" + parent + "'") + ")");
    }

    private String one(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private List<UUID> linesInOrder(UUID order) throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id FROM customer_order_item WHERE order_id = '" + order
                     + "' ORDER BY line_no")) {
            while (rs.next()) {
                out.add(UUID.fromString(rs.getString(1)));
            }
        }
        return out;
    }

    @Test
    void everyExistingLineIsNumberedWithinItsOrderInTheOrderItWasStored() throws Exception {
        assertThat(linesInOrder(kitOrder)).containsExactly(kitLine, kitPolishLine, kitLampLine, kitOilLine);
        assertThat(one("SELECT string_agg(line_no::text, ',' ORDER BY line_no) FROM customer_order_item WHERE order_id = '"
                + kitOrder + "'")).isEqualTo("1,2,3,4");
        assertThat(linesInOrder(plainOrder)).containsExactly(plainLine1, plainLine2);
        assertThat(one("SELECT string_agg(line_no::text, ',' ORDER BY line_no) FROM customer_order_item WHERE order_id = '"
                + plainOrder + "'")).isEqualTo("1,2");
    }

    @Test
    void nothingIsTickedAsPackedAndNoExistingOrderWasEnteredByStaff() throws Exception {
        assertThat(one("SELECT count(*) FROM customer_order_item WHERE packed_at IS NOT NULL OR packed_by IS NOT NULL"))
                .isEqualTo("0");
        assertThat(one("SELECT count(*) FROM customer_order WHERE placed_by IS NOT NULL")).isEqualTo("0");
    }

    @Test
    void theExistingOrdersAreOtherwiseUntouched() throws Exception {
        assertThat(one("SELECT guest_email FROM customer_order WHERE id = '" + kitOrder + "'")).isEqualTo("legacy@example.com");
        assertThat(one("SELECT grand_total::int FROM customer_order WHERE id = '" + kitOrder + "'")).isEqualTo("7500");
        assertThat(one("SELECT count(*) FROM customer_order_item WHERE parent_item_id = '" + kitLine + "'")).isEqualTo("3");
    }

    @Test
    void anOrderCanNowBeStoredWithNoEmail() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO customer_order (id, order_no, guest_email, guest_phone, fulfilment_type, status, subtotal,"
                    + " vat_total, grand_total, delivery_address, customer_name, placed_by) VALUES ('" + id
                    + "', 'SO-STAFF-3', NULL, '254722333444', 'PICKUP', 'PENDING_PAYMENT', 500, 0, 500, 'Kilimani',"
                    + " 'Phone Customer', 'Mary Wanjiku')");
        }
        assertThat(one("SELECT placed_by FROM customer_order WHERE id = '" + id + "'")).isEqualTo("Mary Wanjiku");
        assertThat(one("SELECT count(*) FROM customer_order WHERE id = '" + id + "' AND guest_email IS NULL")).isEqualTo("1");
    }
}
