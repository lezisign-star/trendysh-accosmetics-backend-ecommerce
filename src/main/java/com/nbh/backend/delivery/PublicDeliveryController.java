package com.nbh.backend.delivery;

import com.nbh.backend.delivery.DeliveryDtos.PlaceDto;
import com.nbh.backend.delivery.DeliveryDtos.SuggestRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The checkout's place search + option comparison, and the "please add my area" form. */
@RestController
@RequestMapping("/api/v1/public/delivery")
public class PublicDeliveryController {

    private final DeliveryPlaceService service;

    public PublicDeliveryController(DeliveryPlaceService service) {
        this.service = service;
    }

    @GetMapping("/places")
    public List<PlaceDto> places(@RequestParam(required = false) String q) {
        return service.search(q, true);
    }

    @PostMapping("/suggestions")
    public ResponseEntity<Void> suggest(@Valid @RequestBody SuggestRequest request, HttpServletRequest http) {
        service.suggest(request, http.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }
}
