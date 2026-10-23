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
 * V69 lets a customer pick another colour or size of a product inside a kit: a "customer can change" switch on each kit
 * product, a choice key on each basket line (so the same kit with different picks is a different line) and a table for the
 * picks. This brings a scratch schema to V68, seeds a kit and baskets shaped like the live shop's (a kit with two products,
 * a basket holding the kit and a plain product), and only then applies V69 and the rest: every existing basket line must
 * keep meaning "the kit as it comes" and nothing may be rewritten. V74 then turns swapping off for every kit product
 * (owner, 2026-10-23: off unless staff switch it on; no kit was live yet).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KitChoicesMigrationTest {

    private static final String SCHEMA = "kit_choices_upgrade_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final UUID polish = UUID.randomUUID();
    private final UUID polishRed = UUID.randomUUID();
    private final UUID polishBlack = UUID.randomUUID();
    private final UUID lamp = UUID.randomUUID();
    private final UUID lampVariant = UUID.randomUUID();
    private final UUID kitProduct = UUID.randomUUID();
    private final UUID kitVariant = UUID.randomUUID();
    private final UUID kit = UUID.randomUUID();
    private final UUID profession = UUID.randomUUID();
    private final UUID polishLine = UUID.randomUUID();
    private final UUID lampLine = UUID.randomUUID();
    private final UUID cart = UUID.randomUUID();
    private final UUID kitCartLine = UUID.randomUUID();
    private final UUID lampCartLine = UUID.randomUUID();

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
    void migrateToV68SeedAKitAndABasketThenApplyV69() throws Exception {
        dropSchema();
        flyway("68").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            product(s, polish, "gel-polish", "Gel Polish");
            variant(s, polishRed, polish, "Ruby Red", 850, false);
            variant(s, polishBlack, polish, "Midnight Black", 900, false);
            product(s, lamp, "nail-lamp", "Nail Lamp");
            variant(s, lampVariant, lamp, "Default", 4500, false);
            product(s, kitProduct, "starter-kit", "Starter Kit");
            variant(s, kitVariant, kitProduct, "Default", 5000, true);

            s.execute("INSERT INTO profession (id, slug, label) VALUES ('" + profession + "', 'nail-technician', 'Nail Technician')");
            s.execute("INSERT INTO kit (id, variant_id, profession_id, description) VALUES ('" + kit + "', '" + kitVariant
                    + "', '" + profession + "', 'First nail station')");
            s.execute("INSERT INTO kit_component (id, kit_id, line_no, component_variant_id, qty) VALUES ('" + polishLine
                    + "', '" + kit + "', 1, '" + polishRed + "', 2)");
            s.execute("INSERT INTO kit_component (id, kit_id, line_no, component_variant_id, qty) VALUES ('" + lampLine
                    + "', '" + kit + "', 2, '" + lampVariant + "', 1)");

            s.execute("INSERT INTO cart (id, session_id) VALUES ('" + cart + "', 'legacy-session')");
            cartLine(s, kitCartLine, kitVariant, "Starter Kit", 5000);
            cartLine(s, lampCartLine, lampVariant, "Nail Lamp", 4500);
        }
        flyway("latest").migrate();
    }

    @AfterAll
    void cleanUp() throws Exception {
        dropSchema();
    }

    private void product(Statement s, UUID id, String slug, String name) throws SQLException {
        s.execute("INSERT INTO product (id, slug, name) VALUES ('" + id + "', '" + slug + "', '" + name + "')");
    }

    private void variant(Statement s, UUID id, UUID product, String name, int price, boolean isKit) throws SQLException {
        s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted, is_kit)"
                + " VALUES ('" + id + "', '" + product + "', '" + id.toString().substring(0, 12) + "', '" + name + "', "
                + price + ", true, false, " + isKit + ")");
    }

    private void cartLine(Statement s, UUID id, UUID variant, String name, int price) throws SQLException {
        s.execute("INSERT INTO cart_item (id, cart_id, variant_id, qty, unit_price_snapshot, product_name_snapshot,"
                + " variant_name_snapshot) VALUES ('" + id + "', '" + cart + "', '" + variant + "', 1, " + price + ", '"
                + name + "', 'Default')");
    }

    /** A basket of its own, so a test that adds lines leaves the legacy basket as the migration found it. */
    private UUID newCart(Statement s) throws SQLException {
        UUID id = UUID.randomUUID();
        s.execute("INSERT INTO cart (id, session_id) VALUES ('" + id + "', 'session-" + id + "')");
        return id;
    }

    private void kitLine(Statement s, UUID basket, UUID line, String choiceKey, int price) throws SQLException {
        s.execute("INSERT INTO cart_item (id, cart_id, variant_id, qty, unit_price_snapshot, product_name_snapshot,"
                + " variant_name_snapshot, choice_key) VALUES ('" + line + "', '" + basket + "', '" + kitVariant + "', 1, "
                + price + ", 'Starter Kit', 'Default', '" + choiceKey + "')");
    }

    private String one(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void swappingIsOffForEveryExistingKitProductUntilStaffSwitchItOn() throws Exception {
        assertThat(one("SELECT count(*) FROM kit_component")).isEqualTo("2");
        assertThat(one("SELECT count(*) FROM kit_component WHERE customer_can_change")).isEqualTo("0");
        assertThat(one("SELECT column_default FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND table_name = 'kit_component' AND column_name = 'customer_can_change'")).isEqualTo("false");
    }

    @Test
    void theKitsOwnRowsAreUntouched() throws Exception {
        assertThat(one("SELECT qty::int || '/' || line_no FROM kit_component WHERE id = '" + polishLine + "'")).isEqualTo("2/1");
        assertThat(one("SELECT qty::int || '/' || line_no FROM kit_component WHERE id = '" + lampLine + "'")).isEqualTo("1/2");
    }

    @Test
    void everyExistingBasketLineStillMeansTheKitAsItComes() throws Exception {
        assertThat(one("SELECT count(*) FROM cart_item WHERE cart_id = '" + cart + "'")).isEqualTo("2");
        assertThat(one("SELECT count(*) FROM cart_item WHERE cart_id = '" + cart + "' AND choice_key = ''")).isEqualTo("2");
        assertThat(one("SELECT count(*) FROM cart_item_choice WHERE cart_item_id IN ('" + kitCartLine + "', '" + lampCartLine
                + "')")).isEqualTo("0");
        assertThat(one("SELECT unit_price_snapshot::int FROM cart_item WHERE id = '" + kitCartLine + "'")).isEqualTo("5000");
    }

    @Test
    void theSameKitWithDifferentPicksIsAnotherLineButTheSameWayTwiceIsNot() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            UUID basket = newCart(s);
            // The kit as it comes, and the kit with a pick, are two lines…
            kitLine(s, basket, UUID.randomUUID(), "", 5000);
            kitLine(s, basket, UUID.randomUUID(), "abc123", 5100);
            // …but the same way twice is the same line.
            assertThatThrownBy(() -> kitLine(s, basket, UUID.randomUUID(), "abc123", 5100)).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void picksFollowTheirBasketLineWhenItIsRemoved() throws Exception {
        UUID line = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            kitLine(s, newCart(s), line, "def456", 5050);
            s.execute("INSERT INTO cart_item_choice (cart_item_id, default_variant_id, chosen_variant_id) VALUES ('" + line
                    + "', '" + polishRed + "', '" + polishBlack + "')");
            assertThat(one("SELECT count(*) FROM cart_item_choice WHERE cart_item_id = '" + line + "'")).isEqualTo("1");
            s.execute("DELETE FROM cart_item WHERE id = '" + line + "'");
        }
        assertThat(one("SELECT count(*) FROM cart_item_choice WHERE cart_item_id = '" + line + "'")).isEqualTo("0");
    }
}
