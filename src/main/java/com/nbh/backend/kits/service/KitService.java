package com.nbh.backend.kits.service;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.catalog.service.PhotoOwnership;
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
import com.nbh.backend.stock.service.StockAvailabilityService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

    public KitService(KitRepository kits, KitComponentRepository componentRepository,
                       ProductVariantRepository variantRepository, ProductRepository productRepository,
                       CategoryRepository categoryRepository, ProductImageRepository imageRepository,
                       ProfessionRepository professions, KitAvailabilityService kitAvailability,
                       StockAvailabilityService stockAvailability) {
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

        // Wholesale replace — safe because order lines reference the real component variant
        // directly (see CheckoutService), never a KitComponent row, so no transactional history
        // points at these rows.
        kit.getComponents().clear();
        int lineNo = 1;
        for (ComponentInput c : request.components()) {
            KitComponent kc = new KitComponent();
            kc.setKit(kit);
            kc.setLineNo(lineNo++);
            kc.setComponentVariant(variantById.get(c.variantId()));
            kc.setQty(qtyByVariant.get(c.variantId()));
            kit.getComponents().add(kc);
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

        return active.stream()
                // A kit with NOTHING left purchasable disappears from the storefront entirely — the
                // moment a component is re-enabled it reappears on its own, no admin action needed.
                // A kit can never legitimately have zero components (creation requires at least
                // one), so an absent map entry here means every one of them is unavailable right now.
                .filter(k -> componentsByKit.containsKey(k.getId()))
                .map(k -> toPublicDto(k, componentsByKit.get(k.getId()), imageByVariant,
                        wholeKits.getOrDefault(k.getVariant().getId(), BigDecimal.ZERO).signum() > 0))
                .toList();
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
        Map<UUID, Long> optionCounts = PhotoOwnership.optionCounts(variantRepository.findByProductIdInAndDeletedFalse(productIds));
        java.util.function.Function<ProductVariant, List<String>> gallery = v -> PhotoOwnership.galleryFor(
                        v.getId(), PhotoOwnership.isSingleOption(optionCounts, v.getProduct().getId()),
                        imagesByProduct.getOrDefault(v.getProduct().getId(), List.of()))
                .stream().map(ProductImage::getUrl).toList();

        Map<UUID, BigDecimal> onShelf = stockAvailability.available(
                comps.stream().map(c -> c.getComponentVariant().getId()).toList());
        List<KitDetailDto.Item> items = comps.stream().map(c -> {
            ProductVariant cv = c.getComponentVariant();
            Product cp = cv.getProduct();
            BigDecimal price = cv.getSellingPrice() == null ? BigDecimal.ZERO : cv.getSellingPrice();
            List<String> photos = gallery.apply(cv);
            boolean inStock = onShelf.getOrDefault(cv.getId(), BigDecimal.ZERO).compareTo(c.getQty()) >= 0;
            return new KitDetailDto.Item(c.getId(), cv.getId(), cp.getId(), cp.getSlug(), cp.getName(),
                    CatalogLabel.of(cv), photos.isEmpty() ? null : photos.get(0), photos, c.getQty(), price,
                    price.multiply(c.getQty()), cp.getShortDescription(), cp.getDescription(), cp.getHowToUse(),
                    cp.getIngredients(), inStock);
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

    /** How a variant is named on a kit's page: its colour / size / shade, or nothing for a one-option product. */
    private static final class CatalogLabel {
        private CatalogLabel() {}

        static String of(ProductVariant v) {
            String label = v.shortOption();
            return label == null || label.isBlank() || label.equalsIgnoreCase("Default") ? null : label;
        }
    }

    /** The one method {@code CheckoutService} calls — empty for any variant that isn't a kit, or
     *  for a kit whose components have all since become unavailable. */
    @Transactional(readOnly = true)
    public List<KitComponentLine> componentsForVariant(UUID variantId) {
        return kits.findByVariantId(variantId)
                .map(k -> componentRepository.findByKitIdOrderByLineNoAsc(k.getId()).stream()
                        .filter(c -> KitDtos.isComponentAvailable(c.getComponentVariant()))
                        .map(c -> new KitComponentLine(c.getComponentVariant(), c.getQty()))
                        .toList())
                .orElse(List.of());
    }

    public record KitComponentLine(ProductVariant variant, BigDecimal qtyPerKit) {}

    private KitPublicDto toPublicDto(Kit k, List<KitComponent> comps, Map<UUID, String> imageByVariant,
                                      boolean inStock) {
        ProductVariant variant = k.getVariant();
        Product product = variant.getProduct();
        List<KitPublicDto.Item> items = comps.stream().map(c -> {
            ProductVariant cv = c.getComponentVariant();
            BigDecimal price = cv.getSellingPrice() == null ? BigDecimal.ZERO : cv.getSellingPrice();
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
                packagePrice, sum, sum.subtract(packagePrice).max(BigDecimal.ZERO), inStock, items);
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
