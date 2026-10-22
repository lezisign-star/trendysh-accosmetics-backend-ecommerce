package com.nbh.backend.cart.repository;

import com.nbh.backend.cart.entity.CartItem;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CartItemRepository extends JpaRepository<CartItem, UUID> {
    @EntityGraph(attributePaths = {"variant", "variant.product", "choices"})
    List<CartItem> findByCartId(UUID cartId);

    /** The line for this variant with exactly these kit picks ("" for none): the same kit with different picks is another line. */
    Optional<CartItem> findByCartIdAndVariantIdAndChoiceKey(UUID cartId, UUID variantId, String choiceKey);
}
