package com.nbh.backend.delivery;

import com.nbh.backend.delivery.DeliveryDtos.PlaceDto;
import com.nbh.backend.delivery.DeliveryDtos.ResolveRequest;
import com.nbh.backend.delivery.DeliveryDtos.SavePlaceRequest;
import com.nbh.backend.delivery.DeliveryDtos.SuggestionDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Staff CRUD for delivery places and prices, and the queue of places customers asked for. */
@RestController
@RequestMapping("/api/v1/admin/delivery")
@PreAuthorize("hasAnyAuthority('PERM_SETTINGS_MANAGE','PERM_ORDER_MANAGE_ALL')")
public class AdminDeliveryController {

    private final DeliveryPlaceService service;

    public AdminDeliveryController(DeliveryPlaceService service) {
        this.service = service;
    }

    @GetMapping("/places")
    public List<PlaceDto> list(@RequestParam(required = false) String q) {
        return service.search(q, false);
    }

    @PostMapping("/places")
    public PlaceDto create(@Valid @RequestBody SavePlaceRequest request) {
        return service.create(request);
    }

    @PutMapping("/places/{id}")
    public PlaceDto update(@PathVariable UUID id, @Valid @RequestBody SavePlaceRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/places/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/suggestions")
    public List<SuggestionDto> suggestions() {
        return service.pendingSuggestions();
    }

    @GetMapping("/suggestions/count")
    public Map<String, Long> suggestionCount() {
        return Map.of("count", service.pendingCount());
    }

    @PostMapping("/suggestions/{id}/approve")
    public SuggestionDto approve(@PathVariable UUID id, @RequestBody(required = false) ResolveRequest request) {
        return service.resolve(id, true, request);
    }

    @PostMapping("/suggestions/{id}/reject")
    public SuggestionDto reject(@PathVariable UUID id, @RequestBody(required = false) ResolveRequest request) {
        return service.resolve(id, false, request);
    }
}
