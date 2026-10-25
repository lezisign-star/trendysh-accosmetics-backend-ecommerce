package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.entity.Category;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.repository.CategoryRepository;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.common.exception.ConflictException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Where products and categories move (owner, 2026-10-25): products out of one category or subcategory and into another,
 * everything out of a category in one go (merging two, or emptying one so it can be deleted), and a subcategory under a
 * different top-level category. Two levels only, as everywhere else: a subcategory cannot have subcategories of its own.
 *
 * <p>A product keeps its other categories and the slot the old one had, so a move never changes which category is its
 * primary one (breadcrumbs, SKU prefix) unless the primary is the one being moved, and then the new one takes over.
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

    public CategoryService(CategoryRepository categoryRepository, ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
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
     * {@code fromId} are left alone and counted.
     */
    @Transactional
    public Moved moveProducts(Collection<Product> products, UUID fromId, UUID toId) {
        Category to = requireBothAndDifferent(fromId, toId);
        int moved = 0;
        int alreadyThere = 0;
        int notIn = 0;
        for (Product product : products) {
            List<Category> categories = product.getCategories();
            int at = indexOf(categories, fromId);
            if (at < 0) {
                notIn++;
                continue;
            }
            if (indexOf(categories, toId) >= 0) {
                categories.remove(at);
                alreadyThere++;
            } else {
                categories.set(at, to);
            }
            moved++;
            productRepository.save(product);
        }
        return new Moved(moved, alreadyThere, notIn);
    }

    /**
     * Moves every product filed directly under {@code fromId} (its subcategories' products stay where they are: move or
     * re-parent those on their own) to {@code toId}. With {@code deleteWhenEmpty}, the source is deleted afterwards when
     * nothing is left in it: no products and no subcategories.
     */
    @Transactional
    public MovedEverything moveEverything(UUID fromId, UUID toId, boolean deleteWhenEmpty) {
        Category from = category(fromId);
        requireBothAndDifferent(fromId, toId);
        Moved counts = moveProducts(productRepository.findAllFiledDirectlyIn(fromId), fromId, toId);
        if (!deleteWhenEmpty) {
            return new MovedEverything(counts, false, null);
        }
        long children = categoryRepository.countByParentId(fromId);
        if (children > 0) {
            return new MovedEverything(counts, false, "\"" + from.getName() + "\" was kept: it still has "
                    + children + (children == 1 ? " subcategory" : " subcategories") + ".");
        }
        categoryRepository.flush();
        if (categoryRepository.countProductsReferencing(fromId) > 0) {
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
