package com.nbh.backend.stock;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces that {@code StockPostingService} (and, for reservedQty only,
 * {@code StockReservationService}) are the only classes permitted to write a
 * stock quantity anywhere. This is the mechanical guard against ever repeating
 * Moments Packaging's bug, where a second, uncoordinated write path
 * (RisellerSyncService's bare UPDATE) silently clobbered stock the checkout
 * path never even wrote to in the first place.
 *
 * Audit finding F2 (session 3) proved the original version of this test had
 * two real escape hatches, verified empirically with a throwaway violating
 * class:
 *   1. It matched only {@code save(Object)} by exact overload signature, so
 *      {@code saveAndFlush} — which {@code StockPostingService} itself uses —
 *      slipped through undetected, along with saveAll, delete methods, and
 *      any {@code @Modifying} query.
 *   2. Dirty-checking (loading a {@code StockBalance} in a @Transactional
 *      method and calling a setter with no repository call at all) emits an
 *      UPDATE with no ArchUnit violation whatsoever — this is the Moments bug
 *      reproduced past the guard the design doc specifically wrote to prevent
 *      it.
 *   3. The rule was scoped by PACKAGE (resideOutsideOfPackage
 *      "stock.service..") rather than by the specific classes the design doc
 *      names (§4.7: "no class other than StockPostingService") — any future
 *      class dropped into stock.service (a POS sync job, a bulk importer)
 *      would inherit a free pass.
 *
 * Fixed by matching call targets structurally (owner type + method-name
 * prefix, not one fixed overload) and naming the allowed classes explicitly
 * rather than trusting a package boundary.
 */
class SingleWritePathArchitectureTest {

    private static final com.tngtech.archunit.core.domain.JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nbh.backend");

    private static final String POSTING_SERVICE = "com.nbh.backend.stock.service.StockPostingService";
    private static final String RESERVATION_SERVICE = "com.nbh.backend.stock.service.StockReservationService";

    @Test
    void onlyPostingOrReservationServiceMayMutateLedgerOrBalanceRepositories() {
        DescribedPredicate<JavaCall<?>> mutatesTargetRepo = new DescribedPredicate<>(
                "calls a save*/delete*/insert* method on StockLedgerEntryRepository or StockBalanceRepository") {
            @Override
            public boolean test(JavaCall<?> call) {
                JavaClass owner = call.getTargetOwner();
                boolean isTargetRepo = owner.getFullName().equals("com.nbh.backend.stock.repository.StockLedgerEntryRepository")
                        || owner.getFullName().equals("com.nbh.backend.stock.repository.StockBalanceRepository");
                if (!isTargetRepo) return false;
                String name = call.getName();
                return name.startsWith("save") || name.startsWith("delete") || name.startsWith("insert");
            }
        };

        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(POSTING_SERVICE)
                .and().doNotHaveFullyQualifiedName(RESERVATION_SERVICE)
                .should().callMethodWhere(mutatesTargetRepo)
                .because("StockPostingService (and StockReservationService, for reservedQty) is the single "
                        + "enforced write path for stock quantities — see StockPostingService's class javadoc "
                        + "and the Moments Packaging RisellerSyncService bug it exists to prevent");

        rule.check(CLASSES);
    }

    @Test
    void onlyPostingServiceMaySetOnHandOrCostFieldsOnStockBalance() {
        DescribedPredicate<JavaCall<?>> mutatesQuantityOrCostFields = new DescribedPredicate<>(
                "calls setOnHandQty/setAvgCost/setStockValue/setLastLedgerEntryId on StockBalance") {
            @Override
            public boolean test(JavaCall<?> call) {
                if (!call.getTargetOwner().getFullName().equals("com.nbh.backend.stock.entity.StockBalance")) {
                    return false;
                }
                String name = call.getName();
                return name.equals("setOnHandQty") || name.equals("setAvgCost")
                        || name.equals("setStockValue") || name.equals("setLastLedgerEntryId");
            }
        };

        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(POSTING_SERVICE)
                .should().callMethodWhere(mutatesQuantityOrCostFields)
                .because("dirty-checking a StockBalance's on-hand/cost fields with no repository call is "
                        + "invisible to a repository-method-based rule and reproduces the exact class of bug "
                        + "this whole ledger exists to prevent");

        rule.check(CLASSES);
    }

    @Test
    void onlyPostingOrReservationServiceMaySetReservedQtyOnStockBalance() {
        DescribedPredicate<JavaCall<?>> mutatesReservedQty = new DescribedPredicate<>(
                "calls setReservedQty on StockBalance") {
            @Override
            public boolean test(JavaCall<?> call) {
                return call.getTargetOwner().getFullName().equals("com.nbh.backend.stock.entity.StockBalance")
                        && call.getName().equals("setReservedQty");
            }
        };

        ArchRule rule = noClasses()
                .that().doNotHaveFullyQualifiedName(POSTING_SERVICE)
                .and().doNotHaveFullyQualifiedName(RESERVATION_SERVICE)
                .should().callMethodWhere(mutatesReservedQty)
                .because("reservedQty must only move through StockPostingService (fulfilling a hold) or "
                        + "StockReservationService (reserve/release/fulfill)");

        rule.check(CLASSES);
    }
}
