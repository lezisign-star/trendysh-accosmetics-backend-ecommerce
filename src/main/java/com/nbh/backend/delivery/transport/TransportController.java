package com.nbh.backend.delivery.transport;

import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Metro operators and their drop-off locations: the checkout's lists (public) and the staff screen (admin). */
@RestController
public class TransportController {

    private final TransportService service;

    public TransportController(TransportService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/public/delivery/operators")
    public List<TransportService.OperatorDto> operators() {
        return service.operatorsForShoppers();
    }

    @GetMapping("/api/v1/public/delivery/operators/{id}/stops")
    public List<TransportService.StopDto> stops(@PathVariable UUID id, @RequestParam(defaultValue = "") String q) {
        return service.suggest(id, q.length() > 120 ? q.substring(0, 120) : q);
    }

    @GetMapping("/api/v1/admin/delivery/operators")
    @PreAuthorize("hasAnyAuthority('PERM_SETTINGS_MANAGE','PERM_ORDER_MANAGE_ALL')")
    public List<TransportService.StaffOperatorDto> staffOperators() {
        return service.operatorsForStaff();
    }

    @PostMapping("/api/v1/admin/delivery/operators")
    @PreAuthorize("hasAnyAuthority('PERM_SETTINGS_MANAGE','PERM_ORDER_MANAGE_ALL')")
    public TransportService.StaffOperatorDto addOperator(@Valid @RequestBody TransportService.SaveOperator body) {
        return service.saveOperator(null, body);
    }

    @PutMapping("/api/v1/admin/delivery/operators/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_SETTINGS_MANAGE','PERM_ORDER_MANAGE_ALL')")
    public TransportService.StaffOperatorDto updateOperator(@PathVariable UUID id,
                                                           @Valid @RequestBody TransportService.SaveOperator body) {
        return service.saveOperator(id, body);
    }

    @GetMapping("/api/v1/admin/delivery/operators/{id}/stops")
    @PreAuthorize("hasAnyAuthority('PERM_SETTINGS_MANAGE','PERM_ORDER_MANAGE_ALL')")
    public List<TransportService.StaffStopDto> staffStops(@PathVariable UUID id) {
        return service.stopsForStaff(id);
    }

    @PostMapping("/api/v1/admin/delivery/operators/{id}/stops")
    @PreAuthorize("hasAnyAuthority('PERM_SETTINGS_MANAGE','PERM_ORDER_MANAGE_ALL')")
    public TransportService.StaffStopDto addStop(@PathVariable UUID id,
                                                 @Valid @RequestBody TransportService.SaveStop body) {
        return service.saveStop(id, null, body);
    }

    @PutMapping("/api/v1/admin/delivery/operators/{id}/stops/{stopId}")
    @PreAuthorize("hasAnyAuthority('PERM_SETTINGS_MANAGE','PERM_ORDER_MANAGE_ALL')")
    public TransportService.StaffStopDto updateStop(@PathVariable UUID id, @PathVariable UUID stopId,
                                                    @Valid @RequestBody TransportService.SaveStop body) {
        return service.saveStop(id, stopId, body);
    }
}
