package com.nbh.backend.stock;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SingleWritePathArchitectureTest} enforces the WRITE side ("only StockPostingService/
 * StockReservationService may mutate a stock quantity"). This is the READ-side counterpart —
 * {@code StockAvailabilityService}'s own class javadoc has claimed since it was written that
 * "every reader... goes through this class, never StockBalanceRepository directly", but nothing
 * actually enforced that until Phase 0 (2026-09-27) found three classes doing exactly that
 * (ProductService, AnalyticsController, StockController — all fixed the same day this test was
 * added, by delegating to StockAvailabilityService instead).
 *
 * <p>Why a read-side rule matters, not just a write-side one: a caller that reads
 * {@code StockBalance} straight from the repository sees whatever Hibernate's persistence
 * context happens to be holding for that row in the current transaction, not necessarily a
 * fresh value — the exact class of bug Phase 0's other fix (StockReservationService.terminate,
 * StockPostingService.post) closed for the WRITE paths. Funnelling every read through one class
 * makes it one place to get that right, instead of N places that can each independently get it
 * wrong.
 */
class StockReadPathArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nbh.backend");

    private static final Set<String> READ_RESTRICTED_REPOSITORIES = Set.of(
            "com.nbh.backend.stock.repository.StockBalanceRepository",
            "com.nbh.backend.stock.repository.StockReservationRepository"
    );

    @Test
    void onlyStockServiceOrStockRepositoryMayDependOnBalanceOrReservationRepositories() {
        DescribedPredicate<JavaClass> isReadRestrictedRepository = new DescribedPredicate<>(
                "is StockBalanceRepository or StockReservationRepository") {
            @Override
            public boolean test(JavaClass javaClass) {
                return READ_RESTRICTED_REPOSITORIES.contains(javaClass.getFullName());
            }
        };

        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(
                        "com.nbh.backend.stock.service..", "com.nbh.backend.stock.repository..")
                .should().dependOnClassesThat(isReadRestrictedRepository)
                .because("every stock read — not just every write — must go through "
                        + "StockAvailabilityService (or another stock.service class); see its own "
                        + "class javadoc, and Phase 0's 2026-09-27 fix for the three classes that "
                        + "didn't");

        rule.check(CLASSES);
    }

    /**
     * A repository whose {@code @Query} JPQL string names {@code StockBalance}/
     * {@code StockReservation}/{@code StockLedgerEntry} directly bypasses the read path the
     * same way a Java-level dependency would, but ArchUnit's call-graph analysis above cannot
     * see it — the join happens inside a string Spring Data hands to Hibernate at runtime, not
     * a bytecode call this test's importer can trace. A plain reflection scan over every
     * {@code @Query} annotation's value catches it instead.
     *
     * <p>{@code ProductRepository.searchAdmin} is a known, pre-existing exception (its own
     * comment already calls this out — "still reaches into StockBalance directly via JPQL,
     * bypassing the service... it was already doing this before [the branch-removal work]") —
     * named here explicitly so this test can still catch any NEW violation without first
     * requiring that one query's rewrite, which the codebase has already flagged as its own,
     * separate, larger piece of work (folding it into the storefront/admin search rewrite).
     */
    @Test
    void noQueryStringOutsideStockPackageReferencesLedgerEntitiesDirectly() throws ClassNotFoundException {
        Set<String> knownPreExistingExceptions = Set.of(
                "com.nbh.backend.catalog.repository.ProductRepository#searchAdmin"
        );
        List<String> newViolations = new ArrayList<>();

        for (JavaClass javaClass : CLASSES) {
            if (javaClass.getPackageName().startsWith("com.nbh.backend.stock")) {
                continue; // the stock module itself is exempt — it's the module these entities belong to
            }
            if (!javaClass.isInterface()) {
                continue; // @Query lives on Spring Data repository interfaces
            }
            Class<?> reflected;
            try {
                reflected = javaClass.reflect();
            } catch (Throwable notLoadable) {
                continue; // a handful of ArchUnit-imported types aren't reflectively loadable; irrelevant here
            }
            for (Method method : reflected.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) continue;
                String jpql = query.value();
                boolean referencesLedgerEntity = jpql.contains("StockBalance")
                        || jpql.contains("StockReservation")
                        || jpql.contains("StockLedgerEntry");
                if (!referencesLedgerEntity) continue;

                String key = reflected.getName() + "#" + method.getName();
                if (!knownPreExistingExceptions.contains(key)) {
                    newViolations.add(key);
                }
            }
        }

        assertTrue(newViolations.isEmpty(),
                "New @Query strings outside the stock package reference a stock ledger entity "
                        + "directly, bypassing StockAvailabilityService the same way a Java-level "
                        + "dependency would: " + newViolations);
    }
}
