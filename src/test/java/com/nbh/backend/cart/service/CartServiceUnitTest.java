package com.nbh.backend.cart.service;

import com.nbh.backend.cart.dto.CartDto;
import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.EffectivePriceResolver;
import com.nbh.backend.catalog.service.PromoCampaignService;
import com.nbh.backend.catalog.service.VariantCovers;
import com.nbh.backend.packages.service.PackageAvailabilityService;
import com.nbh.backend.packages.service.PackageService;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * The guest cart's own rules: a basket that was already checked out must come
 * back empty and usable (session_id is unique, so it can't be replaced by a
 * second row), lines merge per variant, and add-time availability is only a
 * courtesy check — not a reservation.
 */
@ExtendWith(MockitoExtension.class)
class CartServiceUnitTest {

    @Mock private CartRepository cartRepository;
    @Mock private CartItemRepository cartItemRepository;
    @Mock private ProductVariantRepository variantRepository;
    @Mock private StockAvailabilityService availabilityService;
    @Mock private PackageAvailabilityService packageAvailability;
    @Mock private PromoCampaignService promoCampaignService;
    @Mock private PackageService packageService;
    @Mock private VariantCovers variantCovers;

    private CartService cartService;
    private Cart cart;
    private ProductVariant variant;

    private static final String SESSION = "guest-session";

    @BeforeEach
    void setUp() {
        // A real resolver (pure function, no repository of its own) plus a mocked campaign service
        // returning no active campaign — these tests aren't about pricing, so the plain selling
        // price is what should end up in unitPriceSnapshot, same as before this dependency existed.
        lenient().when(promoCampaignService.getActiveOrNull()).thenReturn(null);
        cartService = new CartService(cartRepository, cartItemRepository, variantRepository,
                availabilityService, packageAvailability, new EffectivePriceResolver(Clock.systemUTC()), promoCampaignService,
                packageService, variantCovers);

        cart = new Cart();
        cart.setId(UUID.randomUUID());
        cart.setSessionId(SESSION);
        cart.setStatus(Cart.CartStatus.ACTIVE);
        lenient().when(cartRepository.findBySessionId(SESSION)).thenReturn(Optional.of(cart));
        lenient().when(cartRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // A line that is not a package holds nothing inside it, as the real service answers.
        lenient().when(packageService.selection(any(), any())).thenReturn(new PackageService.Selection(List.of(), false));

        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setName("Gel polish");
        product.setSlug("gel-polish");
        product.setPublished(true);
        variant = new ProductVariant();
        variant.setId(UUID.randomUUID());
        variant.setProduct(product);
        variant.setSku("GEL-RED");
        variant.setVariantName("Red");
        variant.setSellingPrice(new BigDecimal("800.00"));
        lenient().when(variantRepository.findById(variant.getId())).thenReturn(Optional.of(variant));
    }

    private void stockAvailable(String onHand, String reserved) {
        when(availabilityService.available(variant.getId()))
                .thenReturn(new BigDecimal(onHand).subtract(new BigDecimal(reserved)));
    }

    @Test
    void aConvertedBasketIsEmptiedAndReopenedInsteadOfLeakingLastOrdersLines() {
        cart.setStatus(Cart.CartStatus.CONVERTED);
        CartItem old = new CartItem();
        old.setVariant(variant);
        old.setQty(BigDecimal.TEN);
        old.setUnitPriceSnapshot(new BigDecimal("800.00"));
        old.setProductNameSnapshot("Gel polish");
        old.setVariantNameSnapshot("Red");
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(old)).thenReturn(List.of());

        CartDto dto = cartService.getOrCreateCart(SESSION);

        assertThat(cart.getStatus()).isEqualTo(Cart.CartStatus.ACTIVE);
        verify(cartItemRepository).deleteAll(List.of(old));
        assertThat(dto.items()).isEmpty();
    }

    @Test
    void addingTheSameVariantTwiceMergesIntoOneLineWithProductIdentityExposed() {
        stockAvailable("10", "0");
        CartItem[] saved = new CartItem[1];
        when(cartItemRepository.findByCartIdAndVariantIdAndChoiceKey(cart.getId(), variant.getId(), ""))
                .thenReturn(Optional.empty())
                .thenAnswer(inv -> Optional.of(saved[0]));
        when(cartItemRepository.save(any())).thenAnswer(inv -> {
            saved[0] = inv.getArgument(0);
            return saved[0];
        });
        when(cartItemRepository.findByCartId(cart.getId())).thenAnswer(inv -> List.of(saved[0]));

        cartService.addItem(SESSION, variant.getId(), new BigDecimal("2"));
        CartDto dto = cartService.addItem(SESSION, variant.getId(), new BigDecimal("3"));

        assertThat(dto.items()).hasSize(1);
        assertThat(dto.items().get(0).qty()).isEqualByComparingTo("5");
        assertThat(dto.items().get(0).productSlug()).isEqualTo("gel-polish");
        assertThat(dto.items().get(0).productId()).isEqualTo(variant.getProduct().getId());
        assertThat(dto.subtotal()).isEqualByComparingTo("4000.00");
    }

    @Test
    void addingMoreThanIsAvailableIsRefusedAndNothingIsSaved() {
        stockAvailable("5", "3");

        // Low stock, so the count is shown — but as the product's name, never the internal SKU.
        assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), new BigDecimal("3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only 2 of Gel polish (Red) left");
        verify(cartItemRepository, never()).save(any());
    }

    @Test
    void aProductWhoseOnlyOptionHasNoRealNameIsNamedWithoutEmptyBrackets() {
        stockAvailable("5", "3");

        // The product editor saves a blank option name for a product with one option; older products carry "Default".
        // Neither says anything, so the shopper reads the product's name alone, not "Gel polish ()".
        for (String placeholder : new String[]{"", "   ", "Default", "default"}) {
            variant.setVariantName(placeholder);
            assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), new BigDecimal("3")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .as("option named \"" + placeholder + "\"")
                    .hasMessage("Only 2 of Gel polish left");
        }
    }

    @Test
    void theRefusalDoesNotRevealHowMuchStockThereReallyIsWhenItIsNotLow() {
        stockAvailable("40", "0");

        assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), new BigDecimal("50")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("That quantity of Gel polish (Red) isn't available right now");
    }

    @Test
    void whatIsAlreadyInTheBasketCountsAgainstStockWhenAddingMore() {
        stockAvailable("3", "0");
        CartItem inBasket = new CartItem();
        inBasket.setCart(cart);
        inBasket.setVariant(variant);
        inBasket.setQty(new BigDecimal("2"));
        when(cartItemRepository.findByCartIdAndVariantIdAndChoiceKey(cart.getId(), variant.getId(), ""))
                .thenReturn(Optional.of(inBasket));

        // 2 already in the basket + 2 more is 4 against 3 in stock: each add used to be compared
        // to stock on its own, so this slipped through until checkout.
        assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), new BigDecimal("2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only 3 of Gel polish (Red) left");
        verify(cartItemRepository, never()).save(any());
    }

    private void variantIsAPackageMaking(String wholePackages) {
        variant.setPackage(true);
        when(packageAvailability.wholePackagesAvailable(List.of(variant.getId())))
                .thenReturn(java.util.Map.of(variant.getId(), new BigDecimal(wholePackages)));
    }

    /** Owner, 2026-10-28: a shopper refused for stock is written down for staff, and the shopper is told the same as before. */
    @Test
    void aRefusalForStockIsWrittenDownForStaffWithoutChangingWhatTheShopperIsTold() {
        var shortfalls = org.mockito.Mockito.mock(com.nbh.backend.stock.service.StockShortfallService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(cartService, "shortfalls", shortfalls);
        variantIsAPackageMaking("0");
        org.mockito.Mockito.doThrow(new RuntimeException("database down")).when(shortfalls)
                .record(any(), any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Gel polish (Red) is out of stock");
        verify(shortfalls).record(variant.getId(), "Gel polish (Red)", BigDecimal.ONE, BigDecimal.ZERO,
                com.nbh.backend.stock.entity.StockShortfallEvent.Source.CART, SESSION, null, null, null);
    }

    @Test
    void aPackageWhoseComponentsAreSoldOutCannotBeAddedToTheCart() {
        variantIsAPackageMaking("0");

        // Packages used to skip this check entirely, so a sold-out package went into the cart and was only
        // refused at the last step of checkout.
        assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Gel polish (Red) is out of stock");
        verify(cartItemRepository, never()).save(any());
        verifyNoInteractions(availabilityService);
    }

    @Test
    void aPackageIsLimitedByHowManyWholePackagesItsComponentsCanStillMakeUp() {
        variantIsAPackageMaking("2");

        assertThatThrownBy(() -> cartService.addItem(SESSION, variant.getId(), new BigDecimal("3")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Only 2 of Gel polish (Red) left");
    }

    /** Lines are listed in the order they were added; a real basket's lines are added at different moments, these are made in a row. */
    private int linesMade = 0;

    private CartItem line(ProductVariant v, String qty, String price) {
        CartItem item = new CartItem();
        item.setId(UUID.randomUUID()); // a stored line always has one; the cart reports each line's shelf by it
        item.setAddedAt(java.time.Instant.parse("2026-10-01T08:00:00Z").plusSeconds(linesMade++));
        item.setCart(cart);
        item.setVariant(v);
        item.setQty(new BigDecimal(qty));
        item.setUnitPriceSnapshot(new BigDecimal(price));
        item.setProductNameSnapshot(v.getProduct().getName());
        item.setVariantNameSnapshot(v.getVariantName());
        return item;
    }

    private ProductVariant aPackage(String sku) {
        ProductVariant pkg = new ProductVariant();
        pkg.setId(UUID.randomUUID());
        pkg.setProduct(variant.getProduct());
        pkg.setSku(sku);
        pkg.setVariantName("Package");
        pkg.setSellingPrice(new BigDecimal("700.00"));
        pkg.setPackage(true);
        return pkg;
    }

    @Test
    void aPackageAndALooseUnitOfItsPartShareOneShelfSoOnlyTheLaterLineIsFlagged() {
        // Two on the shelf. The package (one of them per package) was added first, then two loose ones: together they
        // need three, and checkout would refuse that, so the cart has to show it.
        ProductVariant pkg = aPackage("PKG-A");
        CartItem packageLine = line(pkg, "1", "700.00");
        CartItem looseLine = line(variant, "2", "800.00");
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(packageLine, looseLine));
        when(availabilityService.available(anyCollection())).thenReturn(Map.of(variant.getId(), new BigDecimal("2")));
        when(packageAvailability.partsOf(List.of(pkg.getId())))
                .thenReturn(Map.of(pkg.getId(), List.of(new PackageAvailabilityService.Part(variant, BigDecimal.ONE))));

        CartDto dto = cartService.getOrCreateCart(SESSION);

        assertThat(dto.items()).hasSize(2);
        assertThat(dto.items().get(0).stockLeft()).isEqualByComparingTo("2"); // the package fits: it came first
        assertThat(dto.items().get(1).stockLeft()).isEqualByComparingTo("1"); // 2 asked, 1 left once the package took its part
    }

    @Test
    void twoPackagesSharingAPartAreServedInTheOrderTheyWereAdded() {
        ProductVariant firstPackage = aPackage("PKG-1");
        ProductVariant secondPackage = aPackage("PKG-2");
        CartItem first = line(firstPackage, "2", "700.00");
        CartItem second = line(secondPackage, "1", "700.00");
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(first, second));
        when(availabilityService.available(anyCollection())).thenReturn(Map.of(variant.getId(), new BigDecimal("2")));
        when(packageAvailability.partsOf(anyCollection())).thenReturn(Map.of(
                firstPackage.getId(), List.of(new PackageAvailabilityService.Part(variant, BigDecimal.ONE)),
                secondPackage.getId(), List.of(new PackageAvailabilityService.Part(variant, BigDecimal.ONE))));

        CartDto dto = cartService.getOrCreateCart(SESSION);

        assertThat(dto.items().get(0).stockLeft()).isEqualByComparingTo("2");
        assertThat(dto.items().get(1).stockLeft()).isEqualByComparingTo("0"); // the first package took both
    }

    @Test
    void lineThatsAloneOnTheShelfJustReportsWhatIsThere() {
        CartItem only = line(variant, "1", "800.00");
        when(cartItemRepository.findByCartId(cart.getId())).thenReturn(List.of(only));
        when(availabilityService.available(anyCollection())).thenReturn(Map.of(variant.getId(), new BigDecimal("7")));

        CartDto dto = cartService.getOrCreateCart(SESSION);

        assertThat(dto.items().get(0).stockLeft()).isEqualByComparingTo("7");
    }
}
