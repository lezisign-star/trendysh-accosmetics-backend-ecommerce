package com.nbh.backend.order;

import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductVariant;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductVariantRepository;
import com.nbh.backend.order.dto.AdminOrderDetailDto;
import com.nbh.backend.order.dto.OrderDto;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.CustomerOrderItem;
import com.nbh.backend.order.entity.FulfilmentType;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §4 C5: makes the N+1 regression mechanically visible instead of something
 * that has to be re-noticed by eye every session (the exact defect class
 * audit finding F12 already fixed once, in {@code ProductService.listProducts}
 * — recurring here in code written afterward is the pattern §4 C16 exists to
 * catch structurally; this test is the same idea applied to a specific query
 * instead of a whole rule). Asserts the query COUNT stays flat regardless of
 * item count — the actual signature of "N+1", as opposed to just checking
 * the read works at all (which it would, only slower, one query per item).
 */
@SpringBootTest
@ActiveProfiles("test")
class OrderReadQueryCountTest {

    @Autowired private BranchRepository branchRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private ProductVariantRepository variantRepository;
    @Autowired private CustomerOrderRepository orderRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @Transactional
    void readingAnOrderWithMultipleItemsTakesAFlatNumberOfQueriesNotOnePerItem() {
        Branch branch = branchRepository.findByCode("BR-A").orElseThrow();

        CustomerOrder order = new CustomerOrder();
        order.setOrderNo("QCOUNT-" + UUID.randomUUID().toString().substring(0, 8));
        order.setGuestEmail("qcount@example.com");
        order.setGuestPhone("254700000000");
        order.setFulfilmentBranch(branch);
        order.setFulfilmentType(FulfilmentType.BRANCH_PICKUP);
        order.setStatus(OrderStatus.PENDING_PAYMENT);
        order.setSubtotal(new BigDecimal("1500.00"));
        order.setVatTotal(BigDecimal.ZERO);
        order.setGrandTotal(new BigDecimal("1500.00"));

        for (int i = 0; i < 3; i++) {
            Product product = new Product();
            product.setSlug("qcount-product-" + UUID.randomUUID());
            product.setName("Query Count Product " + i);
            product = productRepository.save(product);

            ProductVariant variant = new ProductVariant();
            variant.setProduct(product);
            variant.setSku("QCOUNT-" + UUID.randomUUID().toString().substring(0, 8));
            variant.setVariantName("Variant " + i);
            variant.setSellingPrice(new BigDecimal("500.00"));
            variant = variantRepository.save(variant);

            CustomerOrderItem item = new CustomerOrderItem();
            item.setOrder(order);
            item.setVariant(variant);
            item.setQty(BigDecimal.ONE);
            item.setUnitPrice(new BigDecimal("500.00"));
            item.setVatRate(BigDecimal.ZERO);
            item.setLineTotal(new BigDecimal("500.00"));
            order.getItems().add(item);
        }

        CustomerOrder saved = orderRepository.save(order);
        UUID orderId = saved.getId();

        // Flush + clear so the read below is a REAL fetch from the DB, not
        // served from the first-level cache we just populated by saving.
        entityManager.flush();
        entityManager.clear();

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        CustomerOrder fetched = orderRepository.findById(orderId).orElseThrow();
        OrderDto dto = OrderDto.from(fetched); // realises items + each item's variant + fulfilmentBranch

        assertThat(dto.items()).hasSize(3);
        long queryCount = statistics.getPrepareStatementCount();
        assertThat(queryCount)
                .as("reading a 3-item order should take a small, flat number of queries — "
                        + "N+1 would show roughly 1 (order+items) + 3 (one per variant) = 4+")
                .isLessThanOrEqualTo(2);

        // The staff detail view additionally names each line's product; that
        // must ride the same entity graph, not add one query per line.
        entityManager.clear();
        statistics.clear();
        CustomerOrder refetched = orderRepository.findById(orderId).orElseThrow();
        AdminOrderDetailDto detail = AdminOrderDetailDto.from(refetched, java.util.List.of());

        assertThat(detail.items()).extracting(AdminOrderDetailDto.Line::productName)
                .containsExactlyInAnyOrder("Query Count Product 0", "Query Count Product 1", "Query Count Product 2");
        assertThat(statistics.getPrepareStatementCount())
                .as("naming each line's product must not add a query per line")
                .isLessThanOrEqualTo(2);
    }
}
