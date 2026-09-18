package com.nbh.backend.cart.repository;

import com.nbh.backend.cart.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {
    Optional<Cart> findBySessionId(String sessionId);

    /**
     * Checkout takes a row lock so two simultaneous submits of the same basket
     * (a double-click, a retry) serialise: the second one sees CONVERTED and is
     * refused instead of creating a duplicate order and a second stock hold.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Cart> findWithLockBySessionId(String sessionId);
}
