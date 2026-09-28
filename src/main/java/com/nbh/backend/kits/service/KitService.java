package com.nbh.backend.kits.service;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductImage;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductImageRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
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

    public KitService(KitRepository kits, KitComponentRepository componentRepository,
                       ProductVariantRepository variantRepository, ProductRepository productRepository,
                       CategoryRepository categoryRepository, ProductImageRepository imageRepository,
                       ProfessionRepository professions) {
        this.kits = kits;
        this.componentRepository = componentRepository;
        this.variantRepository = variantRepository;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.imageRepository = imageRepository;
        this.professions = professions;
    }

    // ---- admin CRUD ----

    @Transactional(readOnly = true)
    public Page<KitAdminDto> list(Pageable pageable) {
        return kits.findAll(pageable).map(KitAdminDto::from);
    }

    @Transactional(readOnly = true)
    public KitAdminDto get(UUID id) {
        return KitAdminDto.from(findOrThrow(id));
    }

    @Transactional
    public KitAdminDto create(SaveKitRequest request) {
        return KitAdminDto.from(saveKit(null, request));
    }

    @Transactional
    public KitAdminDto update(UUID id, SaveKitRequest request) {
        return KitAdminDto.from(saveKit(id, request));
    }

    @Transactional
    public KitAdminDto setActive(UUID id, boolean active) {
        Kit kit = findOrThrow(id);
        // The existing searchSellable() filter on v.isActive is what actually pulls a kit off
        // the shelf — no other change is needed to take one down.
        kit.getVariant().setActive(active);
        variantRepository.save(kit.getVariant());
        return KitAdminDto.from(kit);
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
        List<Kit> active = kits.findAll().stream()
                .filter(k -> k.getVariant().isSellable() && k.getVariant().getProduct().isPublished()
                        && !k.getVariant().getProduct().isDeleted())
                .toList();
        if (active.isEmpty()) {
            return List.of();
        }

        List<UUID> kitIds = active.stream().map(Kit::getId).toList();
        Map<UUID, List<KitComponent>> componentsByKit = componentRepository.findByKitIdInOrderByLineNoAsc(kitIds)
                .stream()
                .collect(Collectors.groupingBy(c -> c.getKit().getId()));

        Set<UUID> productIds = new LinkedHashSet<>();
        for (Kit k : active) {
            productIds.add(k.getVariant().getProduct().getId());
        }
        for (List<KitComponent> comps : componentsByKit.values()) {
            for (KitComponent c : comps) {
                productIds.add(c.getComponentVariant().getProduct().getId());
            }
        }
        Map<UUID, String> imageByProduct = coverImageByProduct(productIds);

        return active.stream()
                .map(k -> toPublicDto(k, componentsByKit.getOrDefault(k.getId(), List.of()), imageByProduct))
                .toList();
    }

    /** The one method {@code CheckoutService} calls — empty for any variant that isn't a kit. */
    @Transactional(readOnly = true)
    public List<KitComponentLine> componentsForVariant(UUID variantId) {
        return kits.findByVariantId(variantId)
                .map(k -> componentRepository.findByKitIdOrderByLineNoAsc(k.getId()).stream()
                        .map(c -> new KitComponentLine(c.getComponentVariant(), c.getQty()))
                        .toList())
                .orElse(List.of());
    }

    public record KitComponentLine(ProductVariant variant, BigDecimal qtyPerKit) {}

    private KitPublicDto toPublicDto(Kit k, List<KitComponent> comps, Map<UUID, String> imageByProduct) {
        ProductVariant variant = k.getVariant();
        Product product = variant.getProduct();
        List<KitPublicDto.Item> items = comps.stream().map(c -> {
            ProductVariant cv = c.getComponentVariant();
            BigDecimal price = cv.getSellingPrice() == null ? BigDecimal.ZERO : cv.getSellingPrice();
            return new KitPublicDto.Item(cv.getId(), cv.getProduct().getName(),
                    imageByProduct.get(cv.getProduct().getId()), c.getQty(), price, price.multiply(c.getQty()));
        }).toList();
        BigDecimal sum = items.stream().map(KitPublicDto.Item::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal packagePrice = variant.getSellingPrice() == null ? BigDecimal.ZERO : variant.getSellingPrice();
        Profession profession = k.getProfession();
        // Real availability is never checked here (a kit holds no stock of its own) — checkout is
        // authoritative, reserving each real component; see ProductVariant#isKit's javadoc.
        return new KitPublicDto(variant.getId(), product.getId(), product.getSlug(), product.getName(),
                profession.getId(), profession.getLabel(), profession.getSlug(), k.getDescription(),
                imageByProduct.get(product.getId()),
                packagePrice, sum, sum.subtract(packagePrice).max(BigDecimal.ZERO), true, items);
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

    /** Product-level cover only (no per-variant override) — simpler than CatalogService's own
     *  image resolution, and enough for a kit tile/contents list. */
    private Map<UUID, String> coverImageByProduct(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> result = new HashMap<>();
        for (ProductImage image : imageRepository.findByProductIdInOrderBySortOrderAsc(productIds)) {
            UUID pid = image.getProduct().getId();
            if (!result.containsKey(pid) || image.isPrimary()) {
                result.put(pid, image.getUrl());
            }
        }
        return result;
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
