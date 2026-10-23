package com.nbh.backend.order.service;

import com.nbh.backend.cart.entity.Cart;
import com.nbh.backend.cart.entity.CartItem;
import com.nbh.backend.cart.repository.CartItemRepository;
import com.nbh.backend.cart.repository.CartRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.PriceChangedException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.dto.PriceChangeDtos.Approver;
import com.nbh.backend.order.dto.PriceChangeDtos.PriceChangeInput;
import com.nbh.backend.order.dto.PriceChangeDtos.PriceChangeView;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.OrderPriceChange;
import com.nbh.backend.order.entity.OrderPriceChange.Acknowledgement;
import com.nbh.backend.order.repository.OrderPriceChangeRepository;
import com.nbh.backend.staff.entity.Permission;
import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffUserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Price changes on orders that staff enter for a customer (owner, 2026-10-23: a customer who phones may have been promised
 * a better price, and the shop wants to see afterwards what was changed, when, why and who agreed).
 *
 * <p>The price is changed on the staff member's own basket, after the shop's prices have been applied and before the order
 * is made, so the order, its VAT, its payment and its stock are all made from the price the customer will pay with no second
 * code path. What this service adds is the record: for every changed line the shop's own price, the new price, the reason,
 * who entered it, and the verbal OK. A person who may approve price changes ({@link Permission#ORDER_PRICE_APPROVE}: the owner
 * and managers) changes a price on their own say-so, though they may still name someone; everyone else must name the owner
 * or manager they agreed it with and write down what was said, and that person then confirms or disputes it on their dashboard.
 * A dispute changes nothing about the order; it puts the price change in front of the owner.
 */
@Service
public class OrderPriceChangeService {

    static final int MAX_CHANGES = 50;

    private final CartRepository carts;
    private final CartItemRepository cartItems;
    private final StaffUserRepository staff;
    private final OrderPriceChangeRepository changes;
    private final Clock clock;

    public OrderPriceChangeService(CartRepository carts, CartItemRepository cartItems, StaffUserRepository staff,
                                   OrderPriceChangeRepository changes, Clock clock) {
        this.carts = carts;
        this.cartItems = cartItems;
        this.staff = staff;
        this.changes = changes;
        this.clock = clock;
    }

    /** One change as applied to the basket: enough to write the record once the order exists. */
    public record Applied(int position, UUID variantId, String line, BigDecimal listUnitPrice, BigDecimal newUnitPrice,
                          String reason, StaffUser confirmer, String whatWasSaid, Acknowledgement ack) {}

    /** What applying the changes left: the changes (by line position in the basket) and the basket's total with them. */
    public record Result(List<Applied> applied, int lineCount, BigDecimal subtotal) {}

    /**
     * Sets the new prices on the basket's lines (in the caller's transaction, so a refusal anywhere leaves the basket as it
     * was) and reports the basket's total with them. Must run after the shop's prices have been applied to the basket.
     */
    @Transactional
    public Result apply(String sessionId, List<PriceChangeInput> inputs, StaffUser enteredBy) {
        Cart cart = carts.findBySessionId(sessionId).filter(c -> c.getStatus() == Cart.CartStatus.ACTIVE)
                .orElseThrow(() -> new UnprocessableException("Add at least one product to the order."));
        List<CartItem> lines = cartItems.findByCartId(cart.getId()).stream().sorted(CartItem.IN_ORDER_ADDED).toList();
        if (inputs.size() > MAX_CHANGES) {
            throw new UnprocessableException("Too many price changes on one order.");
        }
        boolean mayApprove = enteredBy.getResolvedPermissions().contains(Permission.ORDER_PRICE_APPROVE);

        Set<UUID> seen = new HashSet<>();
        java.util.ArrayList<Applied> applied = new java.util.ArrayList<>();
        for (PriceChangeInput input : inputs) {
            if (!seen.add(input.cartItemId())) {
                throw new UnprocessableException("A product can only have one price change.");
            }
            int position = positionOf(lines, input.cartItemId());
            CartItem line = lines.get(position);
            String label = line.getProductNameSnapshot();
            String reason = clean(input.reason());
            if (reason == null) {
                throw new UnprocessableException("Say why the price of " + label + " is changing.");
            }
            BigDecimal listPrice = line.getUnitPriceSnapshot();
            BigDecimal newPrice = input.newUnitPrice().setScale(2, RoundingMode.HALF_UP);
            if (newPrice.compareTo(listPrice) == 0) {
                throw new UnprocessableException("The price of " + label + " is already " + listPrice.toPlainString()
                        + ": enter a different price, or leave it as it is.");
            }
            String said = clean(input.whatWasSaid());
            StaffUser confirmer = null;
            if (input.confirmerId() != null) {
                confirmer = approver(input.confirmerId(), enteredBy);
            } else if (!mayApprove) {
                throw new UnprocessableException("Choose the owner or manager who agreed the new price of " + label + ".");
            }
            if (!mayApprove && said == null) {
                throw new UnprocessableException("Write down what " + confirmer.getFullName()
                        + " said when they agreed the new price of " + label + ".");
            }
            line.setUnitPriceSnapshot(newPrice);
            cartItems.save(line);
            applied.add(new Applied(position, line.getVariant().getId(), label, listPrice, newPrice, reason, confirmer, said,
                    confirmer == null ? Acknowledgement.NOT_NEEDED : Acknowledgement.PENDING));
        }
        BigDecimal subtotal = lines.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Result(applied, lines.size(), subtotal);
    }

    /** The same refusal the checkout gives a shopper whose prices moved, for a total that is not the one the screen showed. */
    public static PriceChangedException totalMoved() {
        return new PriceChangedException("Some prices changed while the order was being entered. The basket has been"
                + " updated: look over the new total and place the order again.");
    }

    /**
     * Writes the record of each change against the order line it produced, once the order exists. The order's top-level
     * lines are in the basket's order (the checkout numbers them as it reads the basket), which is how a change finds its line;
     * a mismatch means something is wrong, and it stops the whole order rather than record a change against the wrong line.
     */
    @Transactional
    public List<OrderPriceChange> record(CustomerOrder order, Result result, StaffUser enteredBy) {
        if (result.applied().isEmpty()) {
            return List.of();
        }
        List<CustomerOrderItem> top = order.getItems().stream().filter(i -> i.getParentItem() == null)
                .sorted(Comparator.comparingInt(CustomerOrderItem::getLineNo)).toList();
        if (top.size() != result.lineCount()) {
            throw new IllegalStateException("The order has " + top.size() + " lines but its basket had " + result.lineCount());
        }
        java.util.ArrayList<OrderPriceChange> saved = new java.util.ArrayList<>();
        for (Applied a : result.applied()) {
            CustomerOrderItem item = top.get(a.position());
            if (!item.getVariant().getId().equals(a.variantId()) || item.getUnitPrice().compareTo(a.newUnitPrice()) != 0) {
                throw new IllegalStateException("A price change does not match the order line it was made on");
            }
            OrderPriceChange row = new OrderPriceChange();
            row.setOrder(order);
            row.setOrderItem(item);
            row.setLineLabel(cut(item.soldLabel(), 300));
            row.setQty(item.getQty());
            row.setListUnitPrice(a.listUnitPrice());
            row.setNewUnitPrice(a.newUnitPrice());
            row.setAmountOff(a.listUnitPrice().subtract(a.newUnitPrice()).multiply(item.getQty()).setScale(2, RoundingMode.HALF_UP));
            row.setReason(a.reason());
            row.setEnteredById(enteredBy.getId());
            row.setEnteredByName(cut(enteredBy.getFullName(), 200));
            if (a.confirmer() != null) {
                row.setConfirmerId(a.confirmer().getId());
                row.setConfirmerName(cut(a.confirmer().getFullName(), 200));
            }
            row.setWhatWasSaid(a.whatWasSaid());
            row.setAckStatus(a.ack());
            saved.add(changes.save(row));
        }
        return saved;
    }

    /** The owner and managers a price change can be agreed with: signed-in-able, may approve, and not the person asking. */
    @Transactional(readOnly = true)
    public List<Approver> approvers(StaffUser asking) {
        return staff.findByDeletedFalseOrderByCreatedAtDesc().stream()
                .filter(u -> canBeNamed(u, asking))
                .sorted(Comparator.comparing(StaffUser::getFullName, String.CASE_INSENSITIVE_ORDER))
                .map(u -> new Approver(u.getId(), u.getFullName(), u.getStaffRole().getDisplayName()))
                .toList();
    }

    /** What is recorded for one order, in the order it was recorded. */
    @Transactional(readOnly = true)
    public List<PriceChangeView> forOrder(UUID orderId) {
        return changes.findByOrderId(orderId).stream().map(PriceChangeView::from).toList();
    }

    /** The changes waiting for this person's answer, the longest waiting first. */
    @Transactional(readOnly = true)
    public List<PriceChangeView> waitingFor(StaffUser person) {
        return changes.namedTo(person.getId(), Acknowledgement.PENDING).stream().map(PriceChangeView::from).toList();
    }

    /** Price changes placed in [from, to), newest first, with any of these answers. */
    @Transactional(readOnly = true)
    public Page<PriceChangeView> inPeriod(Instant from, Instant to, Collection<Acknowledgement> statuses, Pageable pageable) {
        return changes.inPeriod(from, to, statuses.isEmpty() ? List.of(Acknowledgement.values()) : statuses, pageable)
                .map(PriceChangeView::from);
    }

    /**
     * The named confirmer's answer, or that of anyone else who may approve price changes (the controller checks that right):
     * the owner can answer for a manager who is away, and the record says who did. Not the person who entered the change
     * themselves, and not twice. A dispute needs a note saying what is wrong.
     */
    @Transactional
    public PriceChangeView answer(UUID id, boolean confirmed, String note, StaffUser by) {
        OrderPriceChange change = changes.findWithLockById(id)
                .orElseThrow(() -> new NotFoundException("No such price change"));
        if (!change.isWaiting()) {
            throw new ConflictException("This price change was already answered"
                    + (change.getAckByName() == null ? "." : " by " + change.getAckByName() + "."));
        }
        if (by.getId().equals(change.getEnteredById())) {
            throw new AccessDeniedException("You entered this price change, so someone else has to answer it.");
        }
        String cleaned = clean(note);
        if (!confirmed && cleaned == null) {
            throw new UnprocessableException("Say what is wrong with this price change.");
        }
        change.acknowledge(confirmed, by.getId(), cut(by.getFullName(), 200), cleaned, Instant.now(clock));
        return PriceChangeView.from(changes.save(change));
    }

    private StaffUser approver(UUID id, StaffUser enteredBy) {
        StaffUser person = staff.findById(id).orElse(null);
        if (person == null || !canBeNamed(person, enteredBy)) {
            throw new UnprocessableException("Choose the owner or manager who agreed the new price from the list.");
        }
        return person;
    }

    private static boolean canBeNamed(StaffUser person, StaffUser asking) {
        return person.isEnabled() && !person.isMustChangePassword() && person.getStaffRole() != null
                && person.getResolvedPermissions().contains(Permission.ORDER_PRICE_APPROVE)
                && !person.getId().equals(asking.getId());
    }

    private static int positionOf(List<CartItem> lines, UUID cartItemId) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).getId().equals(cartItemId)) {
                return i;
            }
        }
        throw new UnprocessableException("A product with a changed price is no longer in the basket. Look over the basket and try again.");
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
