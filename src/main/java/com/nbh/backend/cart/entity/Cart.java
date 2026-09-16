package com.nbh.backend.cart.entity;

import com.nbh.backend.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Guest-only for now — there is no customer account system yet (architecture
 * doc §2.1 defers full customer auth to later). Keyed on a session id the
 * frontend generates and persists (e.g. in a cookie/localStorage), matching
 * Moments Packaging's Cart, which treats guest carts as first-class rather
 * than an afterthought.
 */
@Getter
@Setter
@Entity
@Table(name = "cart")
public class Cart extends BaseEntity {

    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CartStatus status = CartStatus.ACTIVE;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    public enum CartStatus { ACTIVE, CONVERTED, ABANDONED }
}
