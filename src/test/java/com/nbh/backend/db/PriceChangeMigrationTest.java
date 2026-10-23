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
 * V72 adds the record of a price changed while staff enter an order. This brings a scratch schema to V71, seeds an order
 * and a member of staff shaped like the live ones, then applies V72: the existing rows must be untouched, a price change
 * can be stored against them with the shop's own price beside the new one, an answer can only be one of the four the code
 * knows, a price below nothing is refused, and removing a staff account keeps the record and the names on it.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PriceChangeMigrationTest {

    private static final String SCHEMA = "price_change_upgrade_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final UUID product = UUID.randomUUID();
    private final UUID variant = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID line = UUID.randomUUID();
    private final UUID cashier = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();

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
    void migrateToV71SeedAnOrderThenApplyV72() throws Exception {
        dropSchema();
        flyway("71").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO product (id, slug, name) VALUES ('" + product + "', 'legacy-oil', 'Legacy Oil')");
            s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted, is_kit)"
                    + " VALUES ('" + variant + "', '" + product + "', 'LO-1', 'Default', 450, true, false, false)");
            s.execute("INSERT INTO customer_order (id, order_no, guest_email, guest_phone, fulfilment_type, status, subtotal,"
                    + " vat_total, grand_total, delivery_address, customer_name) VALUES ('" + order + "', 'PC-LEGACY-1',"
                    + " 'legacy@example.com', '254711222333', 'PICKUP', 'PAID', 900, 0, 900, 'Kilimani', 'Legacy Customer')");
            s.execute("INSERT INTO customer_order_item (id, order_id, variant_id, qty, unit_price, vat_rate, line_total)"
                    + " VALUES ('" + line + "', '" + order + "', '" + variant + "', 2, 450, 0, 900)");
            staff(s, cashier, "pc-cashier@example.com", "Cash", "Ier");
            staff(s, manager, "pc-manager@example.com", "Man", "Ager");
        }
        flyway("latest").migrate();
    }

    @AfterAll
    void cleanUp() throws Exception {
        dropSchema();
    }

    private void staff(Statement s, UUID id, String email, String first, String last) throws SQLException {
        s.execute("INSERT INTO staff_user (id, email, password, first_name, last_name) VALUES ('" + id + "', '" + email
                + "', 'x', '" + first + "', '" + last + "')");
    }

    private String one(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private void change(Statement s, UUID id, String newPrice, String ackStatus) throws SQLException {
        s.execute("INSERT INTO order_price_change (id, order_id, order_item_id, line_label, qty, list_unit_price,"
                + " new_unit_price, amount_off, reason, entered_by_id, entered_by_name, confirmer_id, confirmer_name,"
                + " what_was_said, ack_status) VALUES ('" + id + "', '" + order + "', '" + line + "', 'Legacy Oil', 2, 450, "
                + newPrice + ", 100, 'Regular customer', '" + cashier + "', 'Cash Ier', '" + manager + "', 'Man Ager',"
                + " 'Said 400 is fine', '" + ackStatus + "')");
    }

    @Test
    void theExistingOrderAndItsLineAreUntouched() throws Exception {
        assertThat(one("SELECT grand_total::int FROM customer_order WHERE id = '" + order + "'")).isEqualTo("900");
        assertThat(one("SELECT unit_price::int FROM customer_order_item WHERE id = '" + line + "'")).isEqualTo("450");
    }

    @Test
    void aPriceChangeIsKeptWithTheShopsOwnPriceBesideTheNewOne() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            change(s, id, "400", "PENDING");
        }
        assertThat(one("SELECT list_unit_price::int || ' -> ' || new_unit_price::int FROM order_price_change WHERE id = '" + id + "'"))
                .isEqualTo("450 -> 400");
        assertThat(one("SELECT ack_status FROM order_price_change WHERE id = '" + id + "'")).isEqualTo("PENDING");
        assertThat(one("SELECT ack_at IS NULL FROM order_price_change WHERE id = '" + id + "'")).isEqualTo("t");
    }

    @Test
    void anAnswerCanOnlyBeOneOfTheFourTheCodeKnows() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> change(s, UUID.randomUUID(), "400", "MAYBE")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void aPriceBelowNothingIsRefused() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> change(s, UUID.randomUUID(), "-1", "NOT_NEEDED")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void removingAStaffAccountKeepsTheRecordAndTheNamesOnIt() throws Exception {
        UUID leaver = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            staff(s, leaver, "pc-leaver@example.com", "Lea", "Ver");
            s.execute("INSERT INTO order_price_change (id, order_id, order_item_id, line_label, qty, list_unit_price,"
                    + " new_unit_price, amount_off, reason, entered_by_id, entered_by_name, ack_status) VALUES ('" + id
                    + "', '" + order + "', '" + line + "', 'Legacy Oil', 2, 450, 400, 100, 'Regular customer', '" + leaver
                    + "', 'Lea Ver', 'NOT_NEEDED')");
            s.execute("DELETE FROM staff_user WHERE id = '" + leaver + "'");
        }
        assertThat(one("SELECT entered_by_id IS NULL FROM order_price_change WHERE id = '" + id + "'")).isEqualTo("t");
        assertThat(one("SELECT entered_by_name FROM order_price_change WHERE id = '" + id + "'")).isEqualTo("Lea Ver");
    }
}
