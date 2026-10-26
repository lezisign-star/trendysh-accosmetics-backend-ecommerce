package com.nbh.backend.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The way back from V76 (docs/rollback/V76-down.sql): old code cannot start on the renamed schema, and restoring a snapshot
 * would lose every order taken since, so the rename has to be reversible with the data in place. This migrates a scratch
 * schema to the latest version with a package in it, adds a package the way the new code makes one, runs the down script,
 * checks that the old names answer queries with all the rows, and that V76 applies again afterwards.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageRenameRollbackTest {

    private static final String SCHEMA = "package_rename_rollback_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(URL, USER, PASSWORD);
        try (Statement s = connection.createStatement()) {
            s.execute("SET search_path TO " + SCHEMA);
        }
        return connection;
    }

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(URL, USER, PASSWORD).schemas(SCHEMA).defaultSchema(SCHEMA).createSchemas(true)
                .locations("classpath:db/migration").target(target).load();
    }

    private static void dropSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        }
    }

    private String one(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @AfterAll
    void cleanUp() throws Exception {
        dropSchema();
    }

    @Test
    void theDownScriptBringsTheKitNamesBackWithTheirDataAndV76CanBeAppliedAgain() throws Exception {
        UUID component = UUID.randomUUID();
        UUID componentVariant = UUID.randomUUID();
        UUID oldPackageVariant = UUID.randomUUID();
        UUID newPackageVariant = UUID.randomUUID();
        UUID oldPackage = UUID.randomUUID();
        UUID newPackage = UUID.randomUUID();
        UUID trade = UUID.randomUUID();
        UUID extraTrade = UUID.randomUUID();

        dropSchema();
        flyway("75").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            seedProduct(s, "polish", "Polish", componentVariant, "GP-1", "is_kit", false);
            seedProduct(s, "old-package", "Old Package", oldPackageVariant, "KIT-000123", "is_kit", true);
            s.execute("INSERT INTO profession (id, slug, label) VALUES ('" + trade + "', 'nail-technician', 'Nail Technician')");
            s.execute("INSERT INTO profession (id, slug, label) VALUES ('" + extraTrade + "', 'pedicurist', 'Pedicurist')");
            s.execute("INSERT INTO kit (id, variant_id, profession_id, description) VALUES ('" + oldPackage + "', '"
                    + oldPackageVariant + "', '" + trade + "', 'Made before')");
            s.execute("INSERT INTO kit_component (id, kit_id, line_no, component_variant_id, qty) VALUES ('" + component
                    + "', '" + oldPackage + "', 1, '" + componentVariant + "', 2)");
            s.execute("INSERT INTO kit_extra_profession (kit_id, profession_id) VALUES ('" + oldPackage + "', '" + extraTrade + "')");
        }
        flyway("latest").migrate();
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + oldPackageVariant + "'")).isEqualTo("PKG-000123");

        // a package made after V76, the way the new code makes one
        try (Connection c = connect(); Statement s = c.createStatement()) {
            seedProduct(s, "new-package", "New Package", newPackageVariant, "PKG-000200", "is_package", true);
            s.execute("INSERT INTO product_package (id, variant_id, profession_id, description) VALUES ('" + newPackage + "', '"
                    + newPackageVariant + "', '" + trade + "', 'Made after')");
            s.execute("INSERT INTO package_component (id, package_id, line_no, component_variant_id, qty) VALUES ('"
                    + UUID.randomUUID() + "', '" + newPackage + "', 1, '" + componentVariant + "', 1)");
        }

        // the way back
        String down = Files.readString(Path.of("docs", "rollback", "V76-down.sql"));
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(down);
        }

        // the old names answer, with every row
        assertThat(one("SELECT count(*) FROM kit")).isEqualTo("2");
        assertThat(one("SELECT description FROM kit WHERE id = '" + oldPackage + "'")).isEqualTo("Made before");
        assertThat(one("SELECT count(*) FROM kit_component WHERE kit_id = '" + oldPackage + "'")).isEqualTo("1");
        assertThat(one("SELECT qty::int FROM kit_component WHERE id = '" + component + "'")).isEqualTo("2");
        assertThat(one("SELECT profession_id FROM kit_extra_profession WHERE kit_id = '" + oldPackage + "'")).isEqualTo(extraTrade.toString());
        assertThat(one("SELECT count(*) FROM product_variant WHERE is_kit")).isEqualTo("2");
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + oldPackageVariant + "'")).isEqualTo("KIT-000123");
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + newPackageVariant + "'")).isEqualTo("KIT-000200");
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + componentVariant + "'")).isEqualTo("GP-1");
        assertThat(one("SELECT to_regclass('" + SCHEMA + ".product_package') IS NULL")).isEqualTo("t");
        assertThat(one("SELECT count(*) FROM flyway_schema_history WHERE version IN ('76', '77')")).isEqualTo("0");

        // and the new code can come back: V76 is applied again, over the names the script left behind
        flyway("latest").migrate();
        assertThat(one("SELECT count(*) FROM product_package")).isEqualTo("2");
        assertThat(one("SELECT count(*) FROM package_component")).isEqualTo("2");
        assertThat(one("SELECT count(*) FROM package_extra_profession")).isEqualTo("1");
        assertThat(one("SELECT count(*) FROM product_variant WHERE is_package")).isEqualTo("2");
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + newPackageVariant + "'")).isEqualTo("PKG-000200");
        assertThat(one("SELECT to_regclass('" + SCHEMA + ".kit') IS NULL")).isEqualTo("t");
        assertThat(one("SELECT count(*) FROM flyway_schema_history WHERE version = '76' AND success")).isEqualTo("1");
    }

    private void seedProduct(Statement s, String slug, String name, UUID variantId, String sku, String flagColumn, boolean flag)
            throws SQLException {
        UUID product = UUID.randomUUID();
        s.execute("INSERT INTO product (id, slug, name) VALUES ('" + product + "', '" + slug + "', '" + name + "')");
        s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted, " + flagColumn
                + ") VALUES ('" + variantId + "', '" + product + "', '" + sku + "', 'Default', 5000, true, false, " + flag + ")");
    }
}
