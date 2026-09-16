package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.FulfilmentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(
        @NotBlank @Email String guestEmail,
        @NotBlank String guestPhone,
        @NotNull FulfilmentType fulfilmentType,
        String deliveryAddress
) {}
