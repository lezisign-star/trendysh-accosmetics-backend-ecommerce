package com.nbh.backend.order.controller;

import com.nbh.backend.order.dto.StaffOrderDtos.StaffOrderRequest;
import com.nbh.backend.order.dto.StaffOrderDtos.StaffOrderResponse;
import com.nbh.backend.order.service.StaffOrderService;
import com.nbh.backend.staff.entity.StaffUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Orders staff enter for a customer. Same right as managing orders. See {@link StaffOrderService}. */
@RestController
@RequestMapping("/api/v1/admin/orders")
public class StaffOrderController {

    private final StaffOrderService staffOrders;

    public StaffOrderController(StaffOrderService staffOrders) {
        this.staffOrders = staffOrders;
    }

    @PreAuthorize("hasAuthority('PERM_ORDER_MANAGE_ALL')")
    @PostMapping
    public StaffOrderResponse place(@Valid @RequestBody StaffOrderRequest request, @AuthenticationPrincipal StaffUser staff) {
        return staffOrders.place(request, staff);
    }
}
