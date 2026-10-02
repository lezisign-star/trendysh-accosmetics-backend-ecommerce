package com.nbh.backend.tumaboda;

import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.tumaboda.TumaBodaSettlementDtos.OutstandingSummary;
import com.nbh.backend.tumaboda.TumaBodaSettlementDtos.RecordSettlementRequest;
import com.nbh.backend.tumaboda.TumaBodaSettlementDtos.SettlementDto;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** The Payments screen's "TumaBoda settlement" tab — what's owed, past remittances, record a new one. */
@RestController
@RequestMapping("/api/v1/admin/tumaboda-settlement")
@PreAuthorize("hasAnyAuthority('PERM_PAYMENT_MANAGE','PERM_ORDER_MANAGE_ALL')")
public class TumaBodaSettlementController {

    private final TumaBodaSettlementService service;

    public TumaBodaSettlementController(TumaBodaSettlementService service) {
        this.service = service;
    }

    @GetMapping("/outstanding")
    public OutstandingSummary outstanding() {
        return service.outstanding();
    }

    @GetMapping("/history")
    public List<SettlementDto> history() {
        return service.history();
    }

    @GetMapping("/remote-balance")
    public Map<String, BigDecimal> remoteBalance() {
        Map<String, BigDecimal> body = new java.util.HashMap<>();
        service.remoteBalance().ifPresent(b -> body.put("balance", b));
        return body;
    }

    @PostMapping
    public SettlementDto record(@Valid @RequestBody RecordSettlementRequest request,
                                 @AuthenticationPrincipal StaffUser staff) {
        return service.record(request, staff.getId());
    }
}
