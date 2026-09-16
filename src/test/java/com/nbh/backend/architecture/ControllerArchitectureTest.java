package com.nbh.backend.architecture;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * §4 C16 — the systemic fix for the two bug classes that kept recurring
 * WITHOUT a mechanical guard (unlike the stock-write path, which
 * {@code SingleWritePathArchitectureTest} has guarded since session 4 with
 * zero recurrences since). Both rules below are proven against a real
 * violation in this codebase's own history before being written:
 *
 * <ul>
 *   <li>Rule 1 (lazy DTO mapping outside a transaction): session 3's
 *   {@code StockController.balance()} and session 6's near-miss in
 *   {@code OrderController.fulfil()} — both fixed by adding
 *   {@code @Transactional} to the controller method itself.</li>
 *   <li>Rule 2 (mutating endpoint gated on a read-only permission): audit
 *   finding F1 ({@code StockReservationController}, session 3) recurring
 *   verbatim as §4 C3 ({@code PaymentController.confirmManual}/{@code
 *   failManual}, written AFTER F1 was fixed, by the same implementer, in a
 *   different package) — this rule would have failed the build the day C3
 *   was written.</li>
 * </ul>
 */
class ControllerArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.nbh.backend");

    /** Matches a quoted PERM_* authority literal inside a @PreAuthorize SpEL expression. */
    private static final Pattern AUTHORITY_LITERAL = Pattern.compile("'([A-Z_]+)'");

    /**
     * KNOWN GAP, verified empirically while writing this rule: it only sees a
     * DIRECT call — {@code XxxDto.from(entity)} — inside the controller
     * method's own bytecode. A method REFERENCE used the same way
     * ({@code .map(XxxDto::from)}, as several simple list endpoints in this
     * codebase do) compiles to an {@code invokedynamic} call site that this
     * call-graph-based check does not attribute back to the enclosing method,
     * so those call sites are structurally invisible to this rule — confirmed
     * by temporarily removing {@code @Transactional} from {@code
     * BrandController.list()} (a method-reference call site) and observing
     * the rule still pass, then doing the same to {@code
     * CheckoutController.checkout()} (a direct-call site) and observing it
     * correctly fail. Every current method-reference call site this codebase
     * has ({@code BrandController}/{@code CategoryController}/{@code
     * BranchController}'s list-style endpoints) has {@code @Transactional}
     * added anyway as defense in depth — two of them ({@code
     * CategoryDto.from} reading {@code Category.parent}, {@code
     * StockLocationDto.from} reading {@code StockLocation.branch}) genuinely
     * touch a lazy association and would be real bugs without it. This rule
     * still closes the exact bug class both real historical instances
     * (session 3's {@code StockController.balance()}, session 6's {@code
     * OrderController.fulfil()}) actually were: a direct call.
     */
    @Test
    void controllerMethodsThatBuildADtoFromAFreshlyFetchedEntityMustBeTransactional() {
        DescribedPredicate<JavaMethod> callsDtoStaticFromFactory = new DescribedPredicate<>(
                "calls a static from(...) factory on a class in a ..dto.. package") {
            @Override
            public boolean test(JavaMethod method) {
                return method.getCallsFromSelf().stream().anyMatch(call -> {
                    JavaClass owner = call.getTargetOwner();
                    return call.getName().equals("from") && owner.getPackageName().contains(".dto");
                });
            }
        };

        ArchRule rule = methods()
                .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("Controller")
                .and(callsDtoStaticFromFactory)
                .should().beAnnotatedWith(Transactional.class)
                .because("a DTO's static from(...) factory routinely reads lazy associations "
                        + "(open-in-view is false), so a controller method calling one outside its own "
                        + "@Transactional boundary risks a LazyInitializationException the instant that "
                        + "association happens to actually be a not-yet-initialized proxy — audit finding "
                        + "F-something (session 3, StockController.balance()) and a near-miss in session 6 "
                        + "(OrderController.fulfil()) are both this exact bug class");

        rule.check(CLASSES);
    }

    @Test
    void mutatingEndpointsMayNotBeGatedOnlyOnViewPermissions() {
        DescribedPredicate<JavaMethod> isMutatingEndpoint = new DescribedPredicate<>(
                "is annotated with @PostMapping, @PutMapping, @PatchMapping, or @DeleteMapping") {
            @Override
            public boolean test(JavaMethod method) {
                return method.isAnnotatedWith(PostMapping.class)
                        || method.isAnnotatedWith(PutMapping.class)
                        || method.isAnnotatedWith(PatchMapping.class)
                        || method.isAnnotatedWith(DeleteMapping.class);
            }
        };

        ArchCondition<JavaMethod> notGatedOnlyOnViewPermissions = new ArchCondition<>(
                "not be gated only on *_VIEW authorities") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                if (!method.isAnnotatedWith(PreAuthorize.class)) {
                    return; // no gate at all is a different, pre-existing concern — not this rule's job
                }
                String expression = method.getAnnotationOfType(PreAuthorize.class).value();
                List<String> authorities = extractAuthorityLiterals(expression);
                boolean allViewOnly = !authorities.isEmpty() && authorities.stream().allMatch(a -> a.endsWith("_VIEW"));
                if (allViewOnly) {
                    events.add(SimpleConditionEvent.violated(method,
                            method.getFullName() + " is a mutating endpoint (@Post/@Put/@Patch/@DeleteMapping) "
                                    + "whose @PreAuthorize expression grants access via a read-only permission "
                                    + "only: " + expression));
                }
            }
        };

        ArchRule rule = methods()
                .that(isMutatingEndpoint)
                .should(notGatedOnlyOnViewPermissions)
                .because("a *_VIEW permission is, by every convention in this codebase's own Permission enum, "
                        + "read-only — audit finding F1 (session 3, StockReservationController) recurred "
                        + "verbatim as §4 C3 (PaymentController.confirmManual/failManual) in code written AFTER "
                        + "F1 was fixed, proving that fixing one instance does not stop the class from recurring");

        rule.check(CLASSES);
    }

    private static List<String> extractAuthorityLiterals(String preAuthorizeExpression) {
        Matcher matcher = AUTHORITY_LITERAL.matcher(preAuthorizeExpression);
        return matcher.results().map(m -> m.group(1)).toList();
    }
}
