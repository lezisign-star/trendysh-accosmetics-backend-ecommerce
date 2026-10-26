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
 * V76 renames kits to packages (owner, 2026-10-25): three tables, the {@code kit_id} and {@code is_kit} columns, the indexes
 * and constraints named after them, and the SKU prefix of existing packages. This brings a scratch schema to V75 with the
 * old names, seeds data shaped like the live shop's (a package with two products, listed under a second trade, bought once
 * on an order, next to a plain product whose SKU happens to start with KIT-), and only then applies V76: every row must
 * keep its id and its meaning, the order must keep the SKU it was placed with, and the database must stop saying kit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PackageRenameMigrationTest {

    private static final String SCHEMA = "package_rename_upgrade_it";
    private static final String URL = env("DB_URL", "jdbc:postgresql://localhost:5432/nairobi_beauty_hub_test");
    private static final String USER = env("DB_USERNAME", "postgres");
    private static final String PASSWORD = env("DB_PASSWORD", "postgres");

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private final UUID polishProduct = UUID.randomUUID();
    private final UUID polishVariant = UUID.randomUUID();
    private final UUID lampProduct = UUID.randomUUID();
    private final UUID lampVariant = UUID.randomUUID();
    private final UUID looseProduct = UUID.randomUUID();
    private final UUID looseVariant = UUID.randomUUID();
    private final UUID packageProduct = UUID.randomUUID();
    private final UUID packageVariant = UUID.randomUUID();
    private final UUID collidingPackageProduct = UUID.randomUUID();
    private final UUID collidingPackageVariant = UUID.randomUUID();
    private final UUID takenProduct = UUID.randomUUID();
    private final UUID takenVariant = UUID.randomUUID();
    private final UUID pkg = UUID.randomUUID();
    private final UUID collidingPkg = UUID.randomUUID();
    private final UUID mainTrade = UUID.randomUUID();
    private final UUID extraTrade = UUID.randomUUID();
    private final UUID polishLine = UUID.randomUUID();
    private final UUID lampLine = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID orderLine = UUID.randomUUID();

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
    void migrateToV75SeedAPackageAndAnOrderThenApplyV76() throws Exception {
        dropSchema();
        flyway("75").migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            product(s, polishProduct, "gel-polish", "Gel Polish");
            variant(s, polishVariant, polishProduct, "GP-1", false);
            product(s, lampProduct, "nail-lamp", "Nail Lamp");
            variant(s, lampVariant, lampProduct, "NL-1", false);
            // Not a package, only a SKU that starts the same way: the SKU rename must leave it alone.
            product(s, looseProduct, "kit-shaped-thing", "Kit Shaped Thing");
            variant(s, looseVariant, looseProduct, "KIT-LOOSE", false);
            product(s, packageProduct, "starter-package", "Starter Package");
            variant(s, packageVariant, packageProduct, "KIT-000123", true);

            s.execute("INSERT INTO profession (id, slug, label) VALUES ('" + mainTrade + "', 'nail-technician', 'Nail Technician')");
            s.execute("INSERT INTO profession (id, slug, label) VALUES ('" + extraTrade + "', 'pedicurist', 'Pedicurist')");
            s.execute("INSERT INTO kit (id, variant_id, profession_id, description) VALUES ('" + pkg + "', '" + packageVariant
                    + "', '" + mainTrade + "', 'First nail station')");
            s.execute("INSERT INTO kit_component (id, kit_id, line_no, component_variant_id, qty) VALUES ('" + polishLine
                    + "', '" + pkg + "', 1, '" + polishVariant + "', 2)");
            s.execute("INSERT INTO kit_component (id, kit_id, line_no, component_variant_id, qty) VALUES ('" + lampLine
                    + "', '" + pkg + "', 2, '" + lampVariant + "', 1)");
            s.execute("INSERT INTO kit_extra_profession (kit_id, profession_id) VALUES ('" + pkg + "', '" + extraTrade + "')");

            // A package whose new SKU is already taken by another variant (SKUs are unique): it must keep its old one.
            product(s, takenProduct, "pkg-shaped-thing", "Pkg Shaped Thing");
            variant(s, takenVariant, takenProduct, "PKG-000777", false);
            product(s, collidingPackageProduct, "second-package", "Second Package");
            variant(s, collidingPackageVariant, collidingPackageProduct, "KIT-000777", true);
            s.execute("INSERT INTO kit (id, variant_id, profession_id, description) VALUES ('" + collidingPkg + "', '"
                    + collidingPackageVariant + "', '" + mainTrade + "', 'Second one')");

            s.execute("INSERT INTO customer_order (id, order_no, guest_email, guest_phone, fulfilment_type, status, subtotal,"
                    + " vat_total, grand_total, delivery_address, customer_name) VALUES ('" + order + "', 'KR-1', 'legacy@example.com',"
                    + " '254711222333', 'PICKUP', 'PAID', 5000, 0, 5000, 'Kilimani', 'Legacy Customer')");
            s.execute("INSERT INTO customer_order_item (id, order_id, variant_id, qty, unit_price, vat_rate, line_total,"
                    + " sku_snapshot) VALUES ('" + orderLine + "', '" + order + "', '" + packageVariant
                    + "', 1, 5000, 0, 5000, 'KIT-000123')");
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

    private void variant(Statement s, UUID id, UUID product, String sku, boolean isKit) throws SQLException {
        s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted, is_kit)"
                + " VALUES ('" + id + "', '" + product + "', '" + sku + "', 'Default', 5000, true, false, " + isKit + ")");
    }

    private String one(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void theOldNamesAreGoneAndTheNewOnesAreThere() throws Exception {
        for (String gone : new String[] {"kit", "kit_component", "kit_extra_profession"}) {
            assertThat(one("SELECT to_regclass('" + SCHEMA + "." + gone + "') IS NULL")).as(gone + " is gone").isEqualTo("t");
        }
        for (String there : new String[] {"product_package", "package_component", "package_extra_profession"}) {
            assertThat(one("SELECT to_regclass('" + SCHEMA + "." + there + "') IS NOT NULL")).as(there + " exists").isEqualTo("t");
        }
        assertThat(one("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND ((table_name = 'package_component' AND column_name = 'package_id')"
                + " OR (table_name = 'package_extra_profession' AND column_name = 'package_id')"
                + " OR (table_name = 'product_variant' AND column_name = 'is_package'))")).isEqualTo("3");
        assertThat(one("SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()"
                + " AND (column_name IN ('kit_id', 'is_kit') OR table_name LIKE '%kit%')")).isEqualTo("0");
    }

    @Test
    void noIndexOrConstraintIsStillNamedAfterAKit() throws Exception {
        assertThat(one("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = current_schema() AND c.relname LIKE '%kit%'")).isEqualTo("0");
        // Every kind of constraint, NOT NULL ones included: PostgreSQL 18 lists them (named <table>_<column>_not_null at
        // creation) and V76 renames those too; older versions have none, so there is nothing to find there.
        assertThat(one("SELECT count(*) FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = current_schema() AND k.conname LIKE '%kit%'")).isEqualTo("0");
    }

    @Test
    void aPackagesSkuIsLeftAloneWhenAnotherVariantAlreadyHoldsTheNewPrefix() throws Exception {
        // the rename would have made two variants share a SKU, which is unique: that package keeps its old one
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + collidingPackageVariant + "'")).isEqualTo("KIT-000777");
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + takenVariant + "'")).isEqualTo("PKG-000777");
    }

    @Test
    void everyRowKeepsItsIdAndItsMeaning() throws Exception {
        assertThat(one("SELECT count(*) FROM product_package")).isEqualTo("2");
        assertThat(one("SELECT id FROM product_package WHERE variant_id = '" + packageVariant + "'")).isEqualTo(pkg.toString());
        assertThat(one("SELECT description FROM product_package WHERE id = '" + pkg + "'")).isEqualTo("First nail station");
        assertThat(one("SELECT profession_id FROM product_package WHERE id = '" + pkg + "'")).isEqualTo(mainTrade.toString());
        assertThat(one("SELECT count(*) FROM package_component WHERE package_id = '" + pkg + "'")).isEqualTo("2");
        assertThat(one("SELECT qty::int || '/' || line_no FROM package_component WHERE id = '" + polishLine + "'")).isEqualTo("2/1");
        assertThat(one("SELECT qty::int || '/' || line_no FROM package_component WHERE id = '" + lampLine + "'")).isEqualTo("1/2");
        assertThat(one("SELECT profession_id FROM package_extra_profession WHERE package_id = '" + pkg + "'"))
                .isEqualTo(extraTrade.toString());
        assertThat(one("SELECT is_package FROM product_variant WHERE id = '" + packageVariant + "'")).isEqualTo("t");
        assertThat(one("SELECT count(*) FROM product_variant WHERE is_package")).isEqualTo("2");
    }

    @Test
    void aPackagesSkuTakesTheNewPrefixButAPlainProductsSkuAndAnOldOrdersSkuDoNot() throws Exception {
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + packageVariant + "'")).isEqualTo("PKG-000123");
        assertThat(one("SELECT sku FROM product_variant WHERE id = '" + looseVariant + "'")).isEqualTo("KIT-LOOSE");
        assertThat(one("SELECT sku_snapshot FROM customer_order_item WHERE id = '" + orderLine + "'")).isEqualTo("KIT-000123");
    }

    @Test
    void theConstraintsStillHold() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // a component of a package that does not exist
            assertThatThrownBy(() -> s.execute("INSERT INTO package_component (id, package_id, line_no, component_variant_id, qty)"
                    + " VALUES ('" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 3, '" + polishVariant + "', 1)"))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // the same product twice in one package
            assertThatThrownBy(() -> s.execute("INSERT INTO package_component (id, package_id, line_no, component_variant_id, qty)"
                    + " VALUES ('" + UUID.randomUUID() + "', '" + pkg + "', 3, '" + polishVariant + "', 1)"))
                    .isInstanceOf(SQLException.class);
        }
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // one package per product variant
            assertThatThrownBy(() -> s.execute("INSERT INTO product_package (id, variant_id, profession_id, description)"
                    + " VALUES ('" + UUID.randomUUID() + "', '" + packageVariant + "', '" + mainTrade + "', 'again')"))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    void deletingAPackageStillTakesItsProductsAndExtraTradesWithIt() throws Exception {
        UUID product = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        UUID temporary = UUID.randomUUID();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            product(s, product, "temp-package", "Temp Package");
            // After V76 the flag is called is_package (the seeding helper above writes the pre-V76 column).
            s.execute("INSERT INTO product_variant (id, product_id, sku, variant_name, selling_price, is_active, deleted, is_package)"
                    + " VALUES ('" + variantId + "', '" + product + "', 'PKG-TEMP', 'Default', 5000, true, false, true)");
            s.execute("INSERT INTO product_package (id, variant_id, profession_id, description) VALUES ('" + temporary + "', '"
                    + variantId + "', '" + mainTrade + "', 'Temporary')");
            s.execute("INSERT INTO package_component (id, package_id, line_no, component_variant_id, qty) VALUES ('"
                    + UUID.randomUUID() + "', '" + temporary + "', 1, '" + polishVariant + "', 1)");
            s.execute("INSERT INTO package_extra_profession (package_id, profession_id) VALUES ('" + temporary + "', '" + extraTrade + "')");
            s.execute("DELETE FROM product_package WHERE id = '" + temporary + "'");
        }
        assertThat(one("SELECT count(*) FROM package_component WHERE package_id = '" + temporary + "'")).isEqualTo("0");
        assertThat(one("SELECT count(*) FROM package_extra_profession WHERE package_id = '" + temporary + "'")).isEqualTo("0");
    }
}
