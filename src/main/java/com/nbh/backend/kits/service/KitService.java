package com.nbh.backend.kits.service;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.entity.PromoCampaign;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.EffectivePriceResolver;
import com.nbh.backend.catalog.service.PhotoOwnership;
import com.nbh.backend.catalog.service.PromoCampaignService;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.kits.dto.KitDetailDto;
import com.nbh.backend.kits.dto.KitDtos;
import com.nbh.backend.kits.dto.KitDtos.ComponentInput;
import com.nbh.backend.kits.dto.KitDtos.KitAdminDto;
import com.nbh.backend.kits.dto.KitDtos.SaveKitRequest;
import com.nbh.backend.kits.dto.KitPublicDto;
import com.nbh.backend.kits.dto.ProfessionDtos.ProfessionDto;
import com.nbh.backend.kits.dto.ProfessionDtos.ProfessionRequest;
import com.nbh.backend.kits.entity.Kit;
import com.nbh.backend.kits.entity.KitComponent;
import com.nbh.backend.kits.entity.Profession;
import com.nbh.backend.kits.repository.KitComponentRepository;
import com.nbh.backend.kits.repository.KitRepository;
import com.nbh.backend.kits.repository.ProfessionRepository;
import com.nbh.backend.stock.service.QuantityRules;
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Professional Kits: an admin-curated bundle of existing product variants + fixed quantities,
 * sold at one fixed package price. A kit IS a real {@link Product} + {@link ProductVariant} (see
 * {@link ProductVariant#isKit}) — creating/updating one creates/updates that backing product and
 * variant alongside the bundle definition, so it flows through search/category browsing with no
 * changes needed to {@code CatalogService}. The package price is a fixed snapshot on
 * {@code variant.sellingPrice}, set once at save time — never recomputed from the components'
 * current prices; an admin must re-save the kit to refresh it. "Sum of components" and "savings"
 * shown to staff/customers ARE computed live from current prices (see {@code KitAdminDto}/
 * {@link KitPublicDto}), which is what makes those figures honestly reflect today's shelf prices.
 */
@Service
public class KitService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KitRepository kits;
    private final KitComponentRepository componentRepository;
    private final ProductVariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository imageRepository;
    private final ProfessionRepository professions;
    private final KitAvailabilityService kitAvailability;
    private final StockAvailabilityService stockAvailability;
    private final EffectivePriceResolver priceResolver;
    private final PromoCampaignService promoCampaigns;

    public KitService(KitRepository kits, KitComponentRepository componentRepository,
                       ProductVariantRepository variantRepository, ProductRepository productRepository,
                       CategoryRepository categoryRepository, ProductImageRepository imageRepository,
                       ProfessionRepository professions, KitAvailabilityService kitAvailability,
                       StockAvailabilityService stockAvailability, EffectivePriceResolver priceResolver,
                       PromoCampaignService promoCampaigns) {
        this.priceResolver = priceResolver;
        this.promoCampaigns = promoCampaigns;
        this.kits = kits;
        this.componentRepository = componentRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
        this.professions = professions;
        this.kitAvailability = kitAvailability;
        this.stockAvailability = stockAvailability;
    }

    // ---- admin CRUD ----

    @Transactional(readOnly = true)
    public Page<KitAdminDto> list(String q, UUID professionId, Pageable pageable) {
        String query = q == null || q.isBlank() ? null : q.trim();
        Page<Kit> page = kits.searchAdmin(query, professionId, pageable);
        // One lookup for the whole page: each card shows its kit's own thumbnail.
        Map<UUID, String> covers = coverImageByVariant(page.getContent().stream().map(Kit::getVariant).toList());
        return page.map(k -> KitAdminDto.from(k, covers.get(k.getVariant().getId())));
    }

    @Transactional(readOnly = true)
    public KitAdminDto get(UUID id) {
        return adminDto(findOrThrow(id));
    }

    @Transactional
    public KitAdminDto create(SaveKitRequest request) {
        return adminDto(saveKit(null, request));
    }

    @Transactional
    public KitAdminDto update(UUID id, SaveKitRequest request) {
        return adminDto(saveKit(id, request));
    }

    @Transactional
    public KitAdminDto setActive(UUID id, boolean active) {
        Kit kit = findOrThrow(id);
        // The existing searchSellable() filter on v.isActive is what actually pulls a kit off
        // the shelf — no other change is needed to take one down.
        kit.getVariant().setActive(active);
        variantRepository.save(kit.getVariant());
        return adminDto(kit);
    }

    /** A kit as staff see it, with its own thumbnail. */
    private KitAdminDto adminDto(Kit kit) {
        return KitAdminDto.from(kit, coverImageByVariant(List.of(kit.getVariant())).get(kit.getVariant().getId()));
    }

    private Kit saveKit(UUID existingId, SaveKitRequest request) {
        Profession profession = professions.findById(request.professionId())
                .orElseThrow(() -> new NotFoundException("No profession: " + request.professionId()));

        List<UUID> variantIds = request.components().stream().map(ComponentInput::variantId).toList();
        if (variantIds.size() != new HashSet<>(variantIds).size()) {
            throw new UnprocessableException("A kit can't list the same product twice");
        }

        Map<UUID, ProductVariant> variantById = new LinkedHashMap<>();
        Map<UUID, BigDecimal> qtyByVariant = new LinkedHashMap<>();
        for (ComponentInput c : request.components()) {
            ProductVariant v = variantRepository.findById(c.variantId())
                    .orElseThrow(() -> new NotFoundException("No product variant: " + c.variantId()));
            if (v.isKit()) {
                throw new UnprocessableException(
                        v.getProduct().getName() + " is itself a kit — a kit can't contain another kit");
            }
            BigDecimal qty = new BigDecimal(c.qty());
            if (qty.signum() <= 0) {
                throw new UnprocessableException(v.getSku() + ": quantity must be positive");
            }
            // The same rule as every other typed-in quantity: a product counted in pieces goes into a kit in whole pieces.
            QuantityRules.requireSensible(v.getSku(), v, qty);
            variantById.put(c.variantId(), v);
            qtyByVariant.put(c.variantId(), qty);
        }

        BigDecimal sumOfComponents = request.components().stream()
                .map(c -> {
                    ProductVariant v = variantById.get(c.variantId());
                    BigDecimal price = v.getSellingPrice() == null ? BigDecimal.ZERO : v.getSellingPrice();
                    return price.multiply(qtyByVariant.get(c.variantId()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal finalPrice = new BigDecimal(request.finalPrice());
        if (finalPrice.signum() <= 0) {
            throw new UnprocessableException("The kit's price must be positive");
        }
        if (finalPrice.compareTo(sumOfComponents) > 0) {
            throw new UnprocessableException(
                    "The kit price can't be more than its components' combined price (currently " + sumOfComponents + ")");
        }

        Category category = categoryRepository.findById(request.categoryId())
                .orElseThrow(() -> new NotFoundException("No category: " + request.categoryId()));

        Kit kit;
        Product product;
        ProductVariant variant;
        if (existingId == null) {
            // Every required column is set BEFORE this first save: variantRepository.nextSkuSequence()
            // below runs its own query, which Hibernate auto-flushes ahead of — a name set only
            // afterward would already have gone out as NULL in that flush's INSERT.
            product = new Product();
            product.setName(request.name().trim());
            product.setSlug(generateUniqueSlug(request.name()));
            product.setCategories(new ArrayList<>(List.of(category)));
            product.setPublished(true);
            product.setPublishedAt(Instant.now());
            product = productRepository.save(product);

            variant = new ProductVariant();
            variant.setProduct(product);
            variant.setSku("KIT-" + String.format("%06d", variantRepository.nextSkuSequence()));
            variant.setVariantName("Default");
            variant.setKit(true);
            variant.setActive(true);
            variant.setVatExempt(false);

            kit = new Kit();
        } else {
            kit = findOrThrow(existingId);
            variant = kit.getVariant();
            product = variant.getProduct();
            product.setName(request.name().trim());
            product.setCategories(new ArrayList<>(List.of(category)));
            productRepository.save(product);
        }

        variant.setSellingPrice(finalPrice);
        variant = variantRepository.save(variant);
        kit.setVariant(variant);
        kit.setProfession(profession);
        kit.setDescription(request.description().trim());

        // A product that stays in the kit keeps its row and is updated in place; one taken out is removed and a new one
        // added. Clearing the list and adding every row again looked simpler, but Hibernate inserts the new rows BEFORE it
        // deletes the old ones, and a product can only be in a kit once (unique kit_id + variant), so saving a kit whose
        // products had not changed (a new price or description) failed with "A conflicting record already exists".
        Map<UUID, KitComponent> existingByVariant = new HashMap<>();
        for (KitComponent existing : kit.getComponents()) {
            existingByVariant.put(existing.getComponentVariant().getId(), existing);
        }
        Set<UUID> wanted = new HashSet<>(variantIds);
        kit.getComponents().removeIf(existing -> !wanted.contains(existing.getComponentVariant().getId()));
        int lineNo = 1;
        for (ComponentInput c : request.components()) {
            KitComponent kc = existingByVariant.get(c.variantId());
            if (kc == null) {
                kc = new KitComponent();
                kc.setKit(kit);
                kc.setComponentVariant(variantById.get(c.variantId()));
                kit.getComponents().add(kc);
            }
            kc.setLineNo(lineNo++);
            kc.setQty(qtyByVariant.get(c.variantId()));
            kc.setCustomerCanChange(c.canChange());
        }

        return kits.save(kit);
    }

    // ---- public catalog (the "Shop by profession" modal) ----

    @Transactional(readOnly = true)
    public List<KitPublicDto> listPublicKits() {
        boolean demoOn = kits.demoDataOn();
        List<Kit> active = kits.findAll().stream()
                .filter(k -> isOnSale(k, demoOn))
                .toList();
        if (active.isEmpty()) {
            return List.of();
        }

        List<UUID> kitIds = active.stream().map(Kit::getId).toList();
        Map<UUID, List<KitComponent>> componentsByKit = componentRepository.findByKitIdInOrderByLineNoAsc(kitIds)
                .stream()
                // A component whose own product has since been unpublished/deleted/deactivated is
                // silently excluded — the kit keeps selling with what's left, at its unchanged
                // package price. See KitDtos.isComponentAvailable's javadoc.
                .filter(c -> KitDtos.isComponentAvailable(c.getComponentVariant()))
                .collect(Collectors.groupingBy(c -> c.getKit().getId()));

        // Each tile and each component line shows the cover photo of ITS OWN variant: a photo belongs to exactly
        // one option, so a kit never shows a component's sibling's photo.
        Map<UUID, ProductVariant> shown = new LinkedHashMap<>();
        for (Kit k : active) {
            shown.put(k.getVariant().getId(), k.getVariant());
        }
        for (List<KitComponent> comps : componentsByKit.values()) {
            for (KitComponent c : comps) {
                shown.put(c.getComponentVariant().getId(), c.getComponentVariant());
            }
        }
        Map<UUID, String> imageByVariant = coverImageByVariant(shown.values());
        Map<UUID, BigDecimal> wholeKits = kitAvailability.wholeKitsAvailable(active, componentsByKit);
        PromoCampaign campaign = promoCampaigns.getActiveOrNull();
        // A kit can never legitimately have zero components (creation requires at least one), so an absent map entry
        // means every one of them is unavailable right now.
        List<Kit> listed = active.stream().filter(k -> componentsByKit.containsKey(k.getId())).toList();
        Set<UUID> pickToOrder = kitsOrderableWithAnotherOption(listed.stream()
                .filter(k -> wholeKits.getOrDefault(k.getVariant().getId(), BigDecimal.ZERO).signum() <= 0)
                .toList(), componentsByKit);

        // A kit with NOTHING left purchasable disappears from the storefront entirely — the moment a component is
        // re-enabled it reappears on its own, no admin action needed.
        return listed.stream()
                .map(k -> toPublicDto(k, componentsByKit.get(k.getId()), imageByVariant,
                        wholeKits.getOrDefault(k.getVariant().getId(), BigDecimal.ZERO).signum() > 0,
                        pickToOrder.contains(k.getVariant().getId()), campaign))
                .toList();
    }

    /**
     * Of these kits (each out of stock as it comes), the ones a shopper can still order by picking another colour or size
     * on the kit's page: some product is off the shelf in the option the kit comes with, and every such product may be
     * changed and has another option on the shelf (the same options the kit's page offers). Keyed by the kit's variant.
     * Only a hint for the list's card; the kit's page and the cart judge the real picks.
     */
    private Set<UUID> kitsOrderableWithAnotherOption(List<Kit> soldOut, Map<UUID, List<KitComponent>> componentsByKit) {
        if (soldOut.isEmpty()) {
            return Set.of();
        }
        List<KitComponent> comps = soldOut.stream().flatMap(k -> componentsByKit.get(k.getId()).stream()).toList();
        List<UUID> productIds = comps.stream().filter(KitComponent::isCustomerCanChange)
                .map(c -> c.getComponentVariant().getProduct().getId()).distinct().toList();
        Map<UUID, List<ProductVariant>> optionsByProduct = productIds.isEmpty() ? Map.of()
                : variantRepository.findByProductIdInAndDeletedFalse(productIds).stream()
                        .filter(v -> KitDtos.isComponentAvailable(v) && !v.isKit() && !v.getProduct().isMockData())
                        .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
        Set<UUID> shelfIds = new HashSet<>();
        comps.forEach(c -> shelfIds.add(c.getComponentVariant().getId()));
        optionsByProduct.values().forEach(options -> options.forEach(v -> shelfIds.add(v.getId())));
        Map<UUID, BigDecimal> onShelf = stockAvailability.available(shelfIds);

        Set<UUID> orderable = new HashSet<>();
        for (Kit k : soldOut) {
            boolean needsPick = false;
            boolean fixable = true;
            for (KitComponent c : componentsByKit.get(k.getId())) {
                ProductVariant def = c.getComponentVariant();
                if (onShelf.getOrDefault(def.getId(), BigDecimal.ZERO).compareTo(c.getQty()) >= 0) {
                    continue;
                }
                needsPick = true;
                boolean another = c.isCustomerCanChange()
                        && optionsByProduct.getOrDefault(def.getProduct().getId(), List.of()).stream()
                                .anyMatch(v -> !v.getId().equals(def.getId())
                                        && onShelf.getOrDefault(v.getId(), BigDecimal.ZERO).compareTo(c.getQty()) >= 0);
                if (!another) {
                    fixable = false;
                    break;
                }
            }
            if (needsPick && fixable) {
                orderable.add(k.getVariant().getId());
            }
        }
        return orderable;
    }

    /** Whether a kit is on sale right now: its own product is real (or demo, while demo mode is on), live and priced. */
    private static boolean isOnSale(Kit k, boolean demoOn) {
        Product product = k.getVariant().getProduct();
        return product.isMockData() == demoOn && k.getVariant().isSellable() && product.isPublished()
                && !product.isDeleted();
    }

    /**
     * One kit's own page in the shop (owner, 2026-10-19). Like the list, a component whose own product is no longer
     * purchasable is left out, and a kit with nothing left to sell is not found.
     */
    @Transactional(readOnly = true)
    public KitDetailDto publicKit(String slug) {
        boolean demoOn = kits.demoDataOn();
        Kit k = kits.findByProductSlug(slug)
                .filter(kit -> isOnSale(kit, demoOn))
                .orElseThrow(() -> new NotFoundException("No kit: " + slug));
        List<KitComponent> comps = componentRepository.findByKitIdOrderByLineNoAsc(k.getId()).stream()
                .filter(c -> KitDtos.isComponentAvailable(c.getComponentVariant()))
                .toList();
        if (comps.isEmpty()) {
            throw new NotFoundException("No kit: " + slug);
        }

        // Every photo this page can show, found with two queries: each variant shows only ITS OWN gallery.
        ProductVariant kitVariant = k.getVariant();
        List<ProductVariant> shown = new ArrayList<>();
        shown.add(kitVariant);
        comps.forEach(c -> shown.add(c.getComponentVariant()));
        List<UUID> productIds = shown.stream().map(v -> v.getProduct().getId()).distinct().toList();
        Map<UUID, List<ProductImage>> imagesByProduct = imageRepository.findByProductIdInOrderBySortOrderAsc(productIds)
                .stream().collect(Collectors.groupingBy(i -> i.getProduct().getId()));
        List<ProductVariant> liveVariants = variantRepository.findByProductIdInAndDeletedFalse(productIds);
        Map<UUID, Long> optionCounts = PhotoOwnership.optionCounts(liveVariants);
        java.util.function.Function<ProductVariant, List<String>> gallery = v -> PhotoOwnership.galleryFor(
                        v.getId(), PhotoOwnership.isSingleOption(optionCounts, v.getProduct().getId()),
                        imagesByProduct.getOrDefault(v.getProduct().getId(), List.of()))
                .stream().map(ProductImage::getUrl).toList();

        // What a shopper may pick instead for each item they are allowed to change: the buyable variants of its product in
        // the product's own order, the one the kit comes with among them. One option means there is nothing to choose.
        Map<UUID, List<ProductVariant>> variantsByProduct = liveVariants.stream()
                .collect(Collectors.groupingBy(v -> v.getProduct().getId()));
        Map<UUID, List<ProductVariant>> optionsByComponent = new HashMap<>();
        Set<UUID> shelfIds = new HashSet<>();
        for (KitComponent c : comps) {
            ProductVariant cv = c.getComponentVariant();
            shelfIds.add(cv.getId());
            if (!c.isCustomerCanChange()) {
                continue;
            }
            List<ProductVariant> options = variantsByProduct.getOrDefault(cv.getProduct().getId(), List.of()).stream()
                    .filter(v -> v.getId().equals(cv.getId())
                            || (KitDtos.isComponentAvailable(v) && !v.isKit() && !v.getProduct().isMockData()))
                    .sorted(Comparator.comparingInt(ProductVariant::getSortOrder)
                            .thenComparing(v -> v.getVariantName() == null ? "" : v.getVariantName()))
                    .toList();
            if (options.size() > 1) {
                optionsByComponent.put(c.getId(), options);
                options.forEach(v -> shelfIds.add(v.getId()));
            }
        }
        Map<UUID, BigDecimal> onShelf = stockAvailability.available(shelfIds);
        PromoCampaign campaign = promoCampaigns.getActiveOrNull();
        // A pick keeps the kit's discount as a share (see priceDelta): the same arithmetic the cart charges with.
        BigDecimal share = kitShare(priceOf(k.getVariant()), comps.stream()
                .map(c -> priceOf(c.getComponentVariant()).multiply(c.getQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        List<KitDetailDto.Item> items = comps.stream().map(c -> {
            ProductVariant cv = c.getComponentVariant();
            Product cp = cv.getProduct();
            // What a pick does to the kit's price is worked on selling prices (as the cart does); what the product costs
            // "on its own" is what it would be charged bought loose today, deal or campaign included (owner, 2026-10-23).
            BigDecimal price = priceOf(cv);
            BigDecimal loose = loosePrice(cv, campaign);
            List<String> photos = gallery.apply(cv);
            boolean inStock = onShelf.getOrDefault(cv.getId(), BigDecimal.ZERO).compareTo(c.getQty()) >= 0;
            List<ProductVariant> options = optionsByComponent.getOrDefault(c.getId(), List.of());
            List<KitDetailDto.Choice> choices = options.stream().map(v -> {
                List<String> optionPhotos = gallery.apply(v);
                BigDecimal optionPrice = priceOf(v);
                String label = CatalogLabel.of(v);
                return new KitDetailDto.Choice(v.getId(), label != null ? label : v.getVariantName(),
                        v.getOptionColor(), v.getOptionSize(), v.getSwatchHex(), loosePrice(v, campaign),
                        pickDelta(optionPrice.subtract(price).multiply(c.getQty()), share),
                        optionPhotos.isEmpty() ? null : optionPhotos.get(0), optionPhotos,
                        onShelf.getOrDefault(v.getId(), BigDecimal.ZERO).compareTo(c.getQty()) >= 0,
                        v.getId().equals(cv.getId()));
            }).toList();
            return new KitDetailDto.Item(c.getId(), cv.getId(), cp.getId(), cp.getSlug(), cp.getName(),
                    CatalogLabel.of(cv), photos.isEmpty() ? null : photos.get(0), photos, c.getQty(), loose,
                    loose.multiply(c.getQty()), cp.getShortDescription(), cp.getDescription(), cp.getHowToUse(),
                    cp.getIngredients(), inStock, !choices.isEmpty(), choices);
        }).toList();

        BigDecimal sum = items.stream().map(KitDetailDto.Item::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal packagePrice = kitVariant.getSellingPrice() == null ? BigDecimal.ZERO : kitVariant.getSellingPrice();
        BigDecimal whole = kitAvailability.wholeKitsAvailable(List.of(k), Map.of(k.getId(), comps))
                .getOrDefault(kitVariant.getId(), BigDecimal.ZERO);
        List<String> kitPhotos = gallery.apply(kitVariant);
        Product product = kitVariant.getProduct();
        Profession profession = k.getProfession();
        return new KitDetailDto(kitVariant.getId(), product.getId(), product.getSlug(), product.getName(),
                profession.getId(), profession.getLabel(), profession.getSlug(), k.getDescription(),
                kitPhotos.isEmpty() ? null : kitPhotos.get(0), kitPhotos, packagePrice, sum,
                sum.subtract(packagePrice).max(BigDecimal.ZERO), whole.signum() > 0, whole, items);
    }

    /** How an option is named beside its product: its colour / size / shade, or null for a one-option product. */
    public static String optionLabel(ProductVariant v) {
        return CatalogLabel.of(v);
    }

    /** How a variant is named on a kit's page: its colour / size / shade, or nothing for a one-option product. */
    private static final class CatalogLabel {
        private CatalogLabel() {}

        static String of(ProductVariant v) {
            String label = v.shortOption();
            return label == null || label.isBlank() || label.equalsIgnoreCase("Default") ? null : label;
        }
    }

    /**
     * One product of a kit as a shopper takes it: the variant the kit comes with ({@code defaultVariant}) and the one
     * they picked instead ({@code variant}, the same when they changed nothing), with how many ONE kit uses. This is
     * what the cart prices, the shelf is counted against and checkout reserves (owner, 2026-10-19: a shopper may swap the
     * colour or size of a product in a kit, and the price follows).
     */
    public record SelectedPart(UUID componentId, ProductVariant defaultVariant, ProductVariant variant,
                               BigDecimal qtyPerKit, boolean customerCanChange) {
        public boolean changed() {
            return !defaultVariant.getId().equals(variant.getId());
        }

        /** Still purchasable on its own, like every other product of a kit ({@link KitDtos#isComponentAvailable}). */
        public boolean available() {
            return KitDtos.isComponentAvailable(variant);
        }

        /** What ONE kit costs more (less when negative) because of this pick: quantity x (picked price - default price). */
        public BigDecimal unitDelta() {
            return priceOf(variant).subtract(priceOf(defaultVariant)).multiply(qtyPerKit);
        }
    }

    private static BigDecimal priceOf(ProductVariant v) {
        return v.getSellingPrice() == null ? BigDecimal.ZERO : v.getSellingPrice();
    }

    /**
     * What a kit's product costs bought on its own right now: its deal or the running campaign included (never wholesale),
     * so "one by one" and "you save" stay true during a sale. The kit's own price never takes either.
     */
    private BigDecimal loosePrice(ProductVariant v, PromoCampaign campaign) {
        return v.getSellingPrice() == null
                ? BigDecimal.ZERO
                : priceResolver.resolve(v, campaign, BigDecimal.ZERO).effectivePrice();
    }

    /**
     * What a shopper's kit holds with their picks applied ({@link #parts}), and whether any pick of theirs can no longer
     * be honoured ({@link #outOfDate}: staff switched swapping off for the product, or the colour or size they picked is
     * gone). An out-of-date line must be refused with "choose again", never quietly packed as another colour or size.
     */
    public record Selection(List<SelectedPart> parts, boolean outOfDate) {}

    /** Just the products of {@link #selection}, for callers that only need what is inside. */
    @Transactional(readOnly = true)
    public List<SelectedPart> selectionFor(UUID kitVariantId, Map<UUID, UUID> choices) {
        return selection(kitVariantId, choices).parts();
    }

    /**
     * The products of a kit as a shopper with these picks takes them, in the kit's own order; empty for a variant that is
     * not a kit or a kit whose products have all become unavailable. A product the kit comes with that can no longer be
     * bought is left out, as everywhere, unless the shopper picked another colour or size of it that can. A pick that
     * fits but can no longer be bought is KEPT and shows as unavailable, and a pick that no longer fits a product still in
     * the kit is reported as {@link Selection#outOfDate}: a shopper is never silently handed a different colour than the
     * one they chose. A pick is made against the variant the kit came with; if staff have since changed that default to
     * another colour or size of the same product, the pick still finds its product, so the shopper keeps what they chose.
     * A pick for a product that has left the kit is simply moot. This is the one method the cart and checkout expand a kit
     * through.
     */
    @Transactional(readOnly = true)
    public Selection selection(UUID kitVariantId, Map<UUID, UUID> choices) {
        Optional<Kit> kit = kits.findByVariantId(kitVariantId);
        if (kit.isEmpty()) {
            return new Selection(List.of(), false);
        }
        List<KitComponent> all = componentRepository.findByKitIdOrderByLineNoAsc(kit.get().getId());
        Map<UUID, UUID> picks = choices == null ? Map.of() : choices;
        Map<UUID, ProductVariant> picked = picks.isEmpty() ? Map.of()
                : variantRepository.findAllById(picks.values()).stream()
                        .collect(Collectors.toMap(ProductVariant::getId, v -> v, (a, b) -> a));
        Map<UUID, KitComponent> byDefault = new HashMap<>();
        for (KitComponent c : all) {
            byDefault.putIfAbsent(c.getComponentVariant().getId(), c);
        }

        // Which product each pick is for, and what it was swapped to.
        Map<UUID, ProductVariant> chosenFor = new HashMap<>();
        boolean outOfDate = false;
        for (Map.Entry<UUID, UUID> pick : picks.entrySet()) {
            ProductVariant candidate = picked.get(pick.getValue());
            KitComponent component = byDefault.get(pick.getKey());
            if (component == null) {
                if (candidate == null) {
                    outOfDate = true; // neither what it was made against nor what was picked exists any more
                    continue;
                }
                List<KitComponent> ofProduct = all.stream()
                        .filter(c -> c.getComponentVariant().getProduct().getId().equals(candidate.getProduct().getId()))
                        .toList();
                if (ofProduct.isEmpty()) {
                    continue; // the product has left the kit: there is nothing left to pick for
                }
                if (ofProduct.size() > 1) {
                    outOfDate = true; // several shades of that product are in the kit: which one it was for is lost
                    continue;
                }
                component = ofProduct.get(0);
            }
            if (candidate == null || !component.isCustomerCanChange()
                    || !candidate.getProduct().getId().equals(component.getComponentVariant().getProduct().getId())) {
                outOfDate = true;
                continue;
            }
            if (chosenFor.putIfAbsent(component.getId(), candidate) != null) {
                outOfDate = true; // two picks for one product
            }
        }

        List<SelectedPart> parts = new ArrayList<>();
        for (KitComponent c : all) {
            ProductVariant def = c.getComponentVariant();
            ProductVariant chosen = chosenFor.getOrDefault(c.getId(), def);
            boolean swapped = !chosen.getId().equals(def.getId());
            if (!swapped && !KitDtos.isComponentAvailable(def)) {
                continue; // the kit keeps selling with what is left of it
            }
            parts.add(new SelectedPart(c.getId(), def, chosen, c.getQty(), c.isCustomerCanChange()));
        }
        return new Selection(parts, outOfDate);
    }

    /**
     * What ONE kit costs more (or less) with these picks than as it comes; zero for no picks. The kit's discount is a share
     * of its products' price, not a fixed sum (owner, 2026-10-23), so each pick moves the price by its difference in
     * selling price times that share ({@link #pickDelta}): cheaper picks make the kit cheaper by less than they save, and a
     * kit can never become free by swapping.
     */
    @Transactional(readOnly = true)
    public BigDecimal priceDelta(UUID kitVariantId, Map<UUID, UUID> choices) {
        if (choices == null || choices.isEmpty()) {
            return BigDecimal.ZERO;
        }
        List<SelectedPart> parts = selectionFor(kitVariantId, choices);
        BigDecimal packagePrice = kits.findByVariantId(kitVariantId).map(k -> priceOf(k.getVariant()))
                .orElse(BigDecimal.ZERO);
        BigDecimal share = kitShare(packagePrice, parts.stream()
                .map(p -> priceOf(p.defaultVariant()).multiply(p.qtyPerKit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return parts.stream()
                .filter(SelectedPart::changed)
                .map(p -> pickDelta(p.unitDelta(), share))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The kit's price as a share of what its products sell for as it comes (0.8 for a kit at 20% off); 1 when the products
     * have no price to compare with.
     */
    public static BigDecimal kitShare(BigDecimal packagePrice, BigDecimal defaultsTotal) {
        if (defaultsTotal.signum() <= 0) {
            return BigDecimal.ONE;
        }
        return packagePrice.divide(defaultsTotal, 10, RoundingMode.HALF_UP);
    }

    /**
     * What one pick adds to (or takes off) the kit's price: its difference in selling price times the kit's share, in
     * whole shillings. The kit's page shows exactly this per option and the cart adds up the same figures, so the two agree.
     */
    public static BigDecimal pickDelta(BigDecimal sellingDifference, BigDecimal share) {
        return sellingDifference.multiply(share).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Checks what a shopper asks for when they add a kit and keeps only the picks that differ from what it comes with
     * (so "no change" is the empty map and the same picks always make the same basket line). Refuses, with a message
     * the shopper can act on, a pick for a product that is not in the kit, one staff do not allow to be changed, an
     * option that belongs to another product, and one that cannot be bought.
     */
    @Transactional(readOnly = true)
    public Map<UUID, UUID> normalizeChoices(UUID kitVariantId, Map<UUID, UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            return Map.of();
        }
        Kit kit = kits.findByVariantId(kitVariantId)
                .orElseThrow(() -> new UnprocessableException("Only a kit has products inside to change."));
        Map<UUID, KitComponent> byDefault = componentRepository.findByKitIdOrderByLineNoAsc(kit.getId()).stream()
                .filter(c -> KitDtos.isComponentAvailable(c.getComponentVariant()))
                .collect(Collectors.toMap(c -> c.getComponentVariant().getId(), c -> c, (a, b) -> a));
        if (requested.size() > byDefault.size()) {
            // More picks than the kit has products: some are not in it. Refused before anything is looked up.
            throw new UnprocessableException(
                    "One of the products you changed is not in this kit any more. Please reload the kit.");
        }
        Map<UUID, ProductVariant> pickedById = variantRepository.findAllById(
                        requested.values().stream().filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(ProductVariant::getId, v -> v, (a, b) -> a));
        Map<UUID, UUID> kept = new LinkedHashMap<>();
        for (Map.Entry<UUID, UUID> pick : requested.entrySet()) {
            KitComponent component = byDefault.get(pick.getKey());
            if (component == null) {
                throw new UnprocessableException(
                        "One of the products you changed is not in this kit any more. Please reload the kit.");
            }
            ProductVariant def = component.getComponentVariant();
            if (pick.getValue() == null || pick.getValue().equals(def.getId())) {
                continue; // as it comes: nothing to remember
            }
            String product = def.getProduct().getName();
            if (!component.isCustomerCanChange()) {
                throw new UnprocessableException(product + " can't be changed in this kit.");
            }
            ProductVariant picked = pickedById.get(pick.getValue());
            if (picked == null || !picked.getProduct().getId().equals(def.getProduct().getId())) {
                throw new UnprocessableException("Please pick a colour or size of " + product + ".");
            }
            if (!KitDtos.isComponentAvailable(picked) || picked.getProduct().isMockData()) {
                String label = CatalogLabel.of(picked);
                throw new UnprocessableException(
                        product + (label == null ? "" : " (" + label + ")") + " can't be ordered right now.");
            }
            kept.put(def.getId(), picked.getId());
        }
        return kept;
    }

    private KitPublicDto toPublicDto(Kit k, List<KitComponent> comps, Map<UUID, String> imageByVariant,
                                      boolean inStock, boolean pickToOrder, PromoCampaign campaign) {
        ProductVariant variant = k.getVariant();
        Product product = variant.getProduct();
        List<KitPublicDto.Item> items = comps.stream().map(c -> {
            ProductVariant cv = c.getComponentVariant();
            // What it costs bought on its own today, so the card's "you save" stays true during a deal or campaign.
            BigDecimal price = loosePrice(cv, campaign);
            return new KitPublicDto.Item(cv.getId(), cv.getProduct().getName(),
                    imageByVariant.get(cv.getId()), c.getQty(), price, price.multiply(c.getQty()));
        }).toList();
        BigDecimal sum = items.stream().map(KitPublicDto.Item::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal packagePrice = variant.getSellingPrice() == null ? BigDecimal.ZERO : variant.getSellingPrice();
        Profession profession = k.getProfession();
        // A kit holds no stock of its own; it is in stock while every one of its (purchasable) components
        // can still make up at least one whole kit — see KitAvailabilityService. Checkout stays the
        // authority (it reserves each component), this only keeps the shop from offering what is gone.
        return new KitPublicDto(variant.getId(), product.getId(), product.getSlug(), product.getName(),
                profession.getId(), profession.getLabel(), profession.getSlug(), k.getDescription(),
                imageByVariant.get(variant.getId()),
                packagePrice, sum, sum.subtract(packagePrice).max(BigDecimal.ZERO), inStock, pickToOrder, items);
    }

    // ---- professions (kit form's dropdown + inline "+ New" quick-add) ----

    @Transactional(readOnly = true)
    public List<ProfessionDto> listProfessions() {
        return professions.findAllByOrderByLabelAsc().stream().map(ProfessionDto::from).toList();
    }

    @Transactional
    public ProfessionDto createProfession(ProfessionRequest request) {
        String slug = request.slug().trim();
        if (professions.existsBySlug(slug)) {
            throw new UnprocessableException("A profession with slug \"" + slug + "\" already exists");
        }
        Profession profession = new Profession();
        profession.setLabel(request.label().trim());
        profession.setSlug(slug);
        return ProfessionDto.from(professions.save(profession));
    }

    /** Each variant's own cover photo (see PhotoOwnership) — the same rule the shop's cards use. */
    private Map<UUID, String> coverImageByVariant(Collection<ProductVariant> variants) {
        if (variants.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = variants.stream().map(v -> v.getProduct().getId()).distinct().toList();
        List<ProductImage> images = imageRepository.findByProductIdInOrderBySortOrderAsc(productIds);
        Map<UUID, Long> optionCounts = PhotoOwnership.optionCounts(
                variantRepository.findByProductIdInAndDeletedFalse(productIds));
        return PhotoOwnership.coverUrlByVariant(variants, images, optionCounts);
    }

    /**
     * What making a kit's picture with AI starts from: the kit's own product and variant (where the picture is filed),
     * its name and trade, and each product inside that has a photo, with that photo (its own option's main one).
     */
    public record AiPhotoInputs(UUID productId, UUID variantId, String kitName, String professionLabel,
                                List<String> productNames, List<String> photoUrls) {}

    @Transactional(readOnly = true)
    public AiPhotoInputs aiPhotoInputs(UUID kitId) {
        Kit k = findOrThrow(kitId);
        List<ProductVariant> parts = componentRepository.findByKitIdOrderByLineNoAsc(k.getId()).stream()
                .map(KitComponent::getComponentVariant)
                .filter(KitDtos::isComponentAvailable)
                .toList();
        Map<UUID, String> covers = coverImageByVariant(parts);
        List<String> names = new ArrayList<>();
        List<String> photos = new ArrayList<>();
        for (ProductVariant v : parts) {
            String photo = covers.get(v.getId());
            if (photo != null && !photo.isBlank()) {
                names.add(v.getProduct().getName());
                photos.add(photo);
            }
        }
        ProductVariant own = k.getVariant();
        return new AiPhotoInputs(own.getProduct().getId(), own.getId(), own.getProduct().getName(),
                k.getProfession().getLabel(), names, photos);
    }

    private Kit findOrThrow(UUID id) {
        return kits.findById(id).orElseThrow(() -> new NotFoundException("No kit: " + id));
    }

    /** A small local copy of {@code ProductService.generateUniqueSlug} — deliberately not shared,
     *  same isolation {@code PurchaseOrderService} already keeps from the product editor. */
    private String generateUniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (base.isBlank()) {
            base = "kit";
        }
        String candidate = base;
        while (productRepository.existsBySlug(candidate)) {
            candidate = base + "-" + (1000 + RANDOM.nextInt(9000));
        }
        return candidate;
    }
}
