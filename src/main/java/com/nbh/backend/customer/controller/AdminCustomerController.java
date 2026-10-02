package com.nbh.backend.customer.controller;

import com.nbh.backend.common.util.PhoneNumbers;
import com.nbh.backend.customer.dto.AdminCustomerDto;
import com.nbh.backend.order.repository.CustomerOrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The admin Customers screen: everyone who has ordered, built from real orders (not browser storage). */
@RestController
@RequestMapping("/api/v1/admin/customers")
public class AdminCustomerController {

    private final CustomerOrderRepository orders;

    public AdminCustomerController(CustomerOrderRepository orders) {
        this.orders = orders;
    }

    @PreAuthorize("hasAuthority('PERM_CUSTOMER_VIEW')")
    @GetMapping
    public Page<AdminCustomerDto> list(@RequestParam(required = false) String q,
                                       @PageableDefault(size = 25) Pageable pageable) {
        String term = (q == null || q.isBlank()) ? null : q.trim();
        return orders.searchCustomers(term, PhoneNumbers.searchFragment(term), pageable);
    }
}
