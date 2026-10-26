package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.common.event.DataChangedEvent;
import com.nbh.backend.common.exception.ConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Where products and categories move (owner, 2026-10-25): products out of one category or subcategory and into another,
 * everything out of a category in one go (merging two, or emptying one so it can be deleted), and a subcategory under a
 * different top-level category. Two levels only, as everywhere else: a subcategory cannot have subcategories of its own.
 *
 * <p>A product keeps its other categories and the slot the old one had, so a move never changes which category is its
 * primary one (breadcrumbs, SKU prefix) unless the primary is the one being moved, and then the new one takes over.
 * When the product is already under the destination, behind the one it leaves, the destination takes the place of the
 * one it leaves (so a primary stays primary); when it is ahead of it, the one being left is simply taken off.
 * A range whose categories differ files each variant under a category of its own (V68): those move too.
 * Products in the Deleted view and demo products are moved too: they are what stops a category being deleted.
 */
@Service
public class CategoryService {

    /**
     * What a move of products did. {@code moved} products were taken out of the source; of those, {@code alreadyThere} were
     * already under the destination too (so only the source was removed). {@code notIn} selected products were not in the
     * source at all and were left as they are.
     */
    public record Moved(int moved, int alreadyThere, int notIn) {}

    /** {@code deleted} is true when the emptied source category was deleted too; otherwise {@code note} says why not (or is null). */
    public record MovedEverything(Moved counts, boolean deleted, String note) {}

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;
    private final ApplicationEventPublisher events;

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository,
                           ProductVariantRepository variantRepository, ApplicationEventPublisher events) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
        this.events = events;
    }

    /**
     * Two categories with the same name side by side look identical in the shop's menu and in the product
     * form, and nobody can tell which to use. The same name under a different parent is fine ("Accessories"
     * under Nails and under Lashes).
     */
    public void refuseSameNameBeside(String name, Category parent, UUID selfId) {
        List<Category> same = parent == null
                ? categoryRepository.findByParentIsNullAndNameIgnoreCase(name)
                : categoryRepository.findByParentIdAndNameIgnoreCase(parent.getId(), name);
        if (same.stream().anyMatch(c -> !c.getId().equals(selfId))) {
            throw new ConflictException("There is already a category called \"" + name + "\""
                    + (parent == null ? "" : " under " + parent.getName()) + ".");
        }
    }

    /**
     * Takes each of these products out of {@code fromId} and files it under {@code toId} instead. Products that are not in
     * {@code fromId} are left alone and counted. The products' category lists must be loaded or loadable (the caller's
     * transaction is the one this runs in).
     */
    @Transactional
    public Moved moveProducts(Collection<Product> products, UUID fromId, UUID toId) {
        Category to = requireBothAndDifferent(fromId, toId);
        // products with a variant that has the source as ITS OWN category (a range whose categories differ, V68)
        Set<UUID> viaVariant = products.isEmpty() ? Set.of()
                : new HashSet<>(variantRepository.productIdsFiledUnder(fromId, products.stream().map(Product::getId).toList()));
        int moved = 0;
        int alreadyThere = 0;
        int notIn = 0;
        for (Product product : products) {
            int at = indexOf(product.getCategories(), fromId);
            if (at < 0 && !viaVariant.contains(product.getId())) {
                notIn++;
                continue;
            }
            if (at >= 0) {
                // Edit a copy and hand it back. Product has no @Version, so a list changed in place never marks the row as
                // changed: no updatedAt (the editor's stale-form check could not see the move) and no live update.
                List<Category> next = new ArrayList<>(product.getCategories());
                int there = indexOf(next, toId);
                if (there < 0) {
                    next.set(at, to);
                } else {
                    alreadyThere++;
                    if (there > at) {
                        next.set(at, next.get(there)); // it takes the place of the one being left
                        next.remove(there);
                    } else {
                        next.remove(at);
                    }
                }
                product.setCategories(next);
                productRepository.save(product);
            }
            moved++;
        }
        if (!viaVariant.isEmpty()) {
            variantRepository.refileOwnCategory(fromId, to, viaVariant);
            Instant now = Instant.now();
            for (UUID id : viaVariant) {
                productRepository.touch(id, now); // a bulk statement bypasses the row's own bookkeeping
            }
        }
        if (moved > 0) {
            events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.PRODUCTS));
            events.publishEvent(new DataChangedEvent(DataChangedEvent.Topic.CATALOG));
        }
        return new Moved(moved, alreadyThere, notIn);
    }

    /**
     * Moves every product filed directly under {@code fromId} (its subcategories' products stay where they are: move or
     * re-parent those on their own) to {@code toId}. With {@code deleteWhenEmpty}, the source is deleted afterwards when
     * nothing is left in it: no products, no variants filed under it and no subcategories.
     */
    @Transactional
    public MovedEverything moveEverything(UUID fromId, UUID toId, boolean deleteWhenEmpty) {
        Category from = category(fromId);
        requireBothAndDifferent(fromId, toId);
        Map<UUID, Product> affected = new LinkedHashMap<>();
        for (Product p : productRepository.findAllFiledDirectlyIn(fromId)) {
            affected.put(p.getId(), p);
        }
        for (Product p : productRepository.findAllWithVariantsFiledIn(fromId)) {
            affected.putIfAbsent(p.getId(), p);
        }
        Moved counts = moveProducts(affected.values(), fromId, toId);
        if (!deleteWhenEmpty) {
            return new MovedEverything(counts, false, null);
        }
        long children = categoryRepository.countByParentId(fromId);
        if (children > 0) {
            return new MovedEverything(counts, false, "\"" + from.getName() + "\" was kept: it still has "
                    + children + (children == 1 ? " subcategory" : " subcategories") + ".");
        }
        categoryRepository.flush();
        if (categoryRepository.countProductsReferencing(fromId) + categoryRepository.countVariantsFiledUnder(fromId) > 0) {
            return new MovedEverything(counts, false, "\"" + from.getName() + "\" was kept: a product is still filed under it.");
        }
        categoryRepository.delete(from);
        return new MovedEverything(counts, true, null);
    }

    /**
     * Puts a category under another top-level category, or at the top level when {@code newParentId} is null. Its products
     * stay filed under it, so the old and the new parent's counts follow.
     */
    @Transactional
    public Category reparent(UUID id, UUID newParentId) {
        Category category = category(id);
        UUID currentParent = category.getParent() == null ? null : category.getParent().getId();
        if (Objects.equals(currentParent, newParentId)) {
            return category;
        }
        Category parent = null;
        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new IllegalArgumentException("A category can't go under itself.");
            }
            parent = category(newParentId);
            if (parent.getParent() != null) {
                throw new IllegalArgumentException(
                        "Pick a top-level category to move it under: subcategories can't have their own subcategories.");
            }
            long children = categoryRepository.countByParentId(id);
            if (children > 0) {
                throw new ConflictException("\"" + category.getName() + "\" has " + children
                        + (children == 1 ? " subcategory" : " subcategories")
                        + " of its own, so it can't go under another category (only two levels are allowed) — move or delete them first.");
            }
        }
        refuseSameNameBeside(category.getName(), parent, id);
        category.setParent(parent);
        return categoryRepository.save(category);
    }

    private Category requireBothAndDifferent(UUID fromId, UUID toId) {
        if (fromId == null || toId == null) {
            throw new IllegalArgumentException("Choose the category to move from and the one to move to.");
        }
        if (fromId.equals(toId)) {
            throw new IllegalArgumentException("That is the same category: pick a different one to move to.");
        }
        category(fromId);
        return category(toId);
    }

    private Category category(UUID id) {
        return categoryRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("No category: " + id));
    }

    private static int indexOf(List<Category> categories, UUID id) {
        for (int i = 0; i < categories.size(); i++) {
            if (categories.get(i).getId().equals(id)) {
                return i;
            }
        }
        return -1;
    }
}
