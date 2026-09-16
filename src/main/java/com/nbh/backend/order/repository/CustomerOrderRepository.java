package com.nbh.backend.order.repository;

import com.nbh.backend.order.entity.CustomerOrder;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CustomerOrderRepository extends JpaRepository<CustomerOrder, UUID> {

    /**
     * §4 C5 fix: without this, {@code OrderDto.from} (iterating {@code
     * items} then, per item, {@code item.getVariant().getSku()}) and the
     * lazy {@code fulfilmentBranch} reference each incur their own N+1 query
     * — 1 for the order, 1 for the items collection, N for the variants —
     * on every order read. Hit at three call sites:
     * {@code OrderController.get}/{@code .track} and
     * {@code OrderFulfilmentService.fulfil}. One query instead, via a LEFT
     * JOIN FETCH per attribute path.
     */
    @EntityGraph(attributePaths = {"items", "items.variant", "fulfilmentBranch"})
    Optional<CustomerOrder> findByOrderNo(String orderNo);

    @Override
    @EntityGraph(attributePaths = {"items", "items.variant", "fulfilmentBranch"})
    Optional<CustomerOrder> findById(UUID id);
}
