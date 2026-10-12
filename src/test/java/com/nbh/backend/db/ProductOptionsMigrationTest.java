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
 * V63 gives every product its own colour and size list, filled from the variants a live shop already has (the generated
 * ones carry option_color / option_size / swatch_hex since V61). This brings a scratch schema to V62, seeds variants
 * shaped like real ones (a generated colour x size grid in the generator's order, a second spelling of a colour, a dot
 * only on a later variant, a removed variant, a plain product with no options), and only then applies V63 and the rest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProductOptionsMigrationTest {

    private static final String SCHEMA = "product_options_upgrade_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final UUID lipstick = UUID.randomUUID();
    private final UUID rubyMini = UUID.randomUUID();
    private final UUID rubyFull = UUID.randomUUID();
    private final UUID nudeMini = UUID.randomUUID();
    private final UUID nudeFull = UUID.randomUUID();
    private final UUID removedCoral = UUID.randomUUID();

    private final UUID oil = UUID.randomUUID();
    private final UUID oilOption = UUID.randomUUID();

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
    void migrateToV62SeedVariantsThenApplyV63() throws Exception {
        dropSchema();
        flyway("62").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO product (id, slug, name) VALUES ('" + lipstick + "', 'velvet-matte', 'Velvet Matte Lipstick')");
            // The generator's order: every size of the first colour, then the next colour. Nude's first variant has no
            // dot (the list takes the first one that has), and its second spells the colour differently.
            variant(s, rubyMini, lipstick, 0, "Ruby Red", "Mini 2g", "#9B111E", false);
            variant(s, rubyFull, lipstick, 1, "Ruby Red", "Full 3.5g", "#9B111E", false);
            variant(s, nudeMini, lipstick, 2, "Nude Beige", "Mini 2g", null, false);
            variant(s, nudeFull, lipstick, 3, " nude beige ", "Full 3.5g", "#C8A28A", false);
            variant(s, removedCoral, lipstick, 4, "Coral Kiss", "Travel 1g", "#F2705E", true);

            s.execute("INSERT INTO product (id, slug, name) VALUES ('" + oil + "', 'cuticle-oil', 'Cuticle Oil')");
            variant(s, oilOption, oil, 0, null, null, null, false);
        }
        flyway("latest").migrate();
    }

    @AfterAll
    void cleanUp() throws Exception {
        dropSchema();
    }

    private void variant(Statement s, UUID id, UUID product, int sortOrder, String color, String size, String hex,
                         boolean deleted) throws SQLException {
        s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted,"
                + " sort_order, option_color, option_size, swatch_hex) VALUES ('" + id + "', '" + product + "', '"
                + id.toString().substring(0, 12) + "', 'v', 650, true, " + deleted + ", " + sortOrder + ", "
                + quoted(color) + ", " + quoted(size) + ", " + quoted(hex) + ")");
    }

    private static String quoted(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    /** "value|position|swatch" per row of one kind, in position order. */
    private List<String> rows(UUID product, String kind) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT value, position, swatch_hex FROM product_option_value WHERE product_id = '"
                     + product + "' AND kind = '" + kind + "' ORDER BY position")) {
            while (rs.next()) {
                out.add(rs.getString(1) + "|" + rs.getInt(2) + "|" + rs.getString(3));
            }
        }
        return out;
    }

    private Integer sizePosition(UUID variant) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT size_position FROM product_variant WHERE id = '" + variant + "'")) {
            assertThat(rs.next()).isTrue();
            int v = rs.getInt(1);
            return rs.wasNull() ? null : v;
        }
    }

    @Test
    void coloursAreListedOncePerSpellingInTheOrderTheProductFirstHasThemWithTheFirstDotFound() throws Exception {
        assertThat(rows(lipstick, "COLOR")).containsExactly("Ruby Red|0|#9B111E", "Nude Beige|1|#C8A28A");
    }

    @Test
    void sizesAreListedInTheOrderTheGeneratorMadeThemAndARemovedVariantsSizeIsLeftOut() throws Exception {
        assertThat(rows(lipstick, "SIZE")).containsExactly("Mini 2g|0|null", "Full 3.5g|1|null");
    }

    @Test
    void everyLiveVariantKnowsWhereItsSizeSits() throws Exception {
        assertThat(sizePosition(rubyMini)).isEqualTo(0);
        assertThat(sizePosition(rubyFull)).isEqualTo(1);
        assertThat(sizePosition(nudeMini)).isEqualTo(0);
        assertThat(sizePosition(nudeFull)).isEqualTo(1);
        assertThat(sizePosition(removedCoral)).as("removed variants are left alone").isNull();
    }

    @Test
    void aProductWithNoColoursOrSizesGetsNoList() throws Exception {
        assertThat(rows(oil, "COLOR")).isEmpty();
        assertThat(rows(oil, "SIZE")).isEmpty();
        assertThat(sizePosition(oilOption)).isNull();
    }

    @Test
    void theSameValueCannotBeListedTwiceInAnyCase() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.execute(
                    "INSERT INTO product_option_value (product_id, kind, value, position) VALUES ('" + lipstick
                            + "', 'COLOR', 'RUBY RED', 9)"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
