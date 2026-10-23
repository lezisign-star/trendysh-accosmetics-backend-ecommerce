package com.nbh.backend.order.controller;

import com.nbh.backend.order.dto.PriceChangeDtos.Approver;
import com.nbh.backend.order.dto.StaffOrderDtos.CustomerLookup;
import com.nbh.backend.order.dto.StaffOrderDtos.StaffOrderRequest;
import com.nbh.backend.order.dto.StaffOrderDtos.StaffOrderResponse;
import com.nbh.backend.order.service.OrderPriceChangeService;
import com.nbh.backend.order.service.StaffOrderService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Orders staff enter for a customer who phoned, messaged or stood at the counter: every role but the read-only one
 * ({@code ORDER_ENTER}), and anyone who could already manage orders. See {@link StaffOrderService}.
 */
@RestController
@RequestMapping("/api/v1/admin/orders")
@PreAuthorize("hasAnyAuthority('PERM_ORDER_ENTER','PERM_ORDER_MANAGE_ALL')")
public class StaffOrderController {

    private final StaffOrderService staffOrders;
    private final OrderPriceChangeService priceChanges;

    public StaffOrderController(StaffOrderService staffOrders, OrderPriceChangeService priceChanges) {
        this.staffOrders = staffOrders;
        this.priceChanges = priceChanges;
    }

    @PostMapping
    public StaffOrderResponse place(@Valid @RequestBody StaffOrderRequest request, @AuthenticationPrincipal StaffUser staff) {
        return staffOrders.place(request, staff);
    }

    /** Name, email and the place last asked for, from the newest order placed with this number. */
    @GetMapping("/customer-lookup")
    public CustomerLookup customerLookup(@RequestParam String phone) {
        return staffOrders.lookupCustomer(phone);
    }

    /** The owner and managers a price change can be agreed with (never the person asking). */
    @GetMapping("/price-approvers")
    public List<Approver> priceApprovers(@AuthenticationPrincipal StaffUser staff) {
        return priceChanges.approvers(staff);
    }
}
