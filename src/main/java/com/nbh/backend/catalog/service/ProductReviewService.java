package com.nbh.backend.catalog.service;

import com.nbh.backend.catalog.dto.ProductReviewDto;
import com.nbh.backend.catalog.dto.SubmitReviewRequest;
import com.nbh.backend.catalog.entity.Product;
import com.nbh.backend.catalog.entity.ProductReview;
import com.nbh.backend.catalog.repository.ProductRepository;
import com.nbh.backend.catalog.repository.ProductReviewRepository;
import com.nbh.backend.common.exception.ConflictException;
import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.common.exception.NotFoundException;
import com.nbh.backend.common.exception.UnprocessableException;
import com.nbh.backend.order.entity.CustomerOrder;
import com.nbh.backend.order.entity.OrderStatus;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Review eligibility stands in for a customer login (checkout is guest-only,
 * architecture doc §4.7): a review is only accepted when the caller can name
 * a real order for the phone number it was actually placed with, that order
 * contains the product being reviewed, and payment on it succeeded. None of
 * this is exposed as "why did my review fail" detail beyond what's needed —
 * a vague 404 for a wrong order/phone combination avoids letting a caller
 * enumerate real order numbers.
 */
@Service
public class ProductReviewService {

    /** Anything before PAID means payment never actually succeeded — see OrderStatus's javadoc for the happy path. */
    private static final Set<OrderStatus> ELIGIBLE_STATUSES =
            EnumSet.of(OrderStatus.PAID, OrderStatus.PICKING, OrderStatus.READY,
                    OrderStatus.DISPATCHED, OrderStatus.DELIVERED);

    private final ProductRepository productRepository;
    private final ProductReviewRepository reviewRepository;
    private final CustomerOrderRepository orderRepository;
    private final Clock clock;

    public ProductReviewService(ProductRepository productRepository,
                                 ProductReviewRepository reviewRepository,
                                 CustomerOrderRepository orderRepository,
                                 Clock clock) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.clock = clock;
    }

    @Transactional
    public ProductReviewDto submit(UUID productId, SubmitReviewRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("No product: " + productId));

        CustomerOrder order = orderRepository.findByOrderNo(request.orderNo().trim())
                .filter(o -> PhoneNumbers.sameNumber(o.getGuestPhone(), request.phone()))
                .orElseThrow(() -> new NotFoundException("No matching order found for that order number and phone"));

        if (!ELIGIBLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException("This order hasn't been paid yet — reviews open once payment is confirmed");
        }

        boolean orderedThisProduct = order.getItems().stream()
                .anyMatch(item -> item.getVariant().getProduct().getId().equals(productId));
        if (!orderedThisProduct) {
            throw new UnprocessableException("That order doesn't include this product");
        }

        if (reviewRepository.existsByOrderIdAndProductId(order.getId(), productId)) {
            throw new ConflictException("This order has already been used to review this product");
        }

        ProductReview review = new ProductReview();
        review.setProduct(product);
        review.setOrder(order);
        review.setRating(request.rating());
        review.setComment(request.comment());
        review.setReviewerName(request.reviewerName().trim());
        review.setReviewerPhone(request.phone().trim());
        review.setStatus(ProductReview.ReviewStatus.PENDING);

        return ProductReviewDto.from(reviewRepository.save(review));
    }

    /**
     * A review from someone already proven to own {@code order} (the track page's verified-email session), so
     * no phone check is needed. The same rules otherwise: the order must be paid, include the product, and a
     * product can be reviewed once per order. Lands PENDING for staff to approve.
     */
    @Transactional
    public ProductReviewDto submitForVerifiedOrder(CustomerOrder order, UUID productId, int rating, String comment,
                                                   String reviewerName) {
        if (!ELIGIBLE_STATUSES.contains(order.getStatus())) {
            throw new UnprocessableException("This order hasn't been paid yet — reviews open once payment is confirmed");
        }
        boolean orderedThisProduct = order.getItems().stream()
                .anyMatch(item -> item.getVariant().getProduct().getId().equals(productId));
        if (!orderedThisProduct) {
            throw new UnprocessableException("That order doesn't include this product");
        }
        if (reviewRepository.existsByOrderIdAndProductId(order.getId(), productId)) {
            throw new ConflictException("This order has already been used to review this product");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("No product: " + productId));
        ProductReview review = new ProductReview();
        review.setProduct(product);
        review.setOrder(order);
        review.setRating(rating);
        review.setComment(comment);
        review.setReviewerName(reviewerName.trim());
        review.setReviewerPhone(order.getGuestPhone());
        review.setStatus(ProductReview.ReviewStatus.PENDING);
        return ProductReviewDto.from(reviewRepository.save(review));
    }

    @Transactional(readOnly = true)
    public Page<com.nbh.backend.catalog.dto.AdminReviewDto> listForStaff(ProductReview.ReviewStatus status, Pageable pageable) {
        return reviewRepository.searchForStaff(status, pageable).map(com.nbh.backend.catalog.dto.AdminReviewDto::from);
    }

    /**
     * A product's approved reviews, for its storefront page. Only for a product a shopper can actually see:
     * an unpublished or deleted one (including demo products while demo data is off) answers 404 like its
     * product page does, instead of handing its reviews to anyone who knows the id.
     */
    @Transactional(readOnly = true)
    public Page<ProductReviewDto> listApproved(UUID productId, Pageable pageable) {
        productRepository.findById(productId)
                .filter(p -> p.isPublished() && !p.isDeleted())
                .orElseThrow(() -> new NotFoundException("No product: " + productId));
        return reviewRepository
                .findByProductIdAndStatusOrderByCreatedAtDesc(productId, ProductReview.ReviewStatus.APPROVED, pageable)
                .map(ProductReviewDto::from);
    }

    @Transactional
    public ProductReviewDto moderate(UUID reviewId, ProductReview.ReviewStatus newStatus, UUID staffId) {
        if (newStatus != ProductReview.ReviewStatus.APPROVED && newStatus != ProductReview.ReviewStatus.REJECTED) {
            throw new UnprocessableException("A review can only be moderated to APPROVED or REJECTED");
        }
        ProductReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new NotFoundException("No review: " + reviewId));
        review.setStatus(newStatus);
        review.setModeratedBy(staffId);
        review.setModeratedAt(Instant.now(clock));
        return ProductReviewDto.from(reviewRepository.save(review));
    }
}
