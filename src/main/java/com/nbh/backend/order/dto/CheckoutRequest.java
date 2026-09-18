package com.nbh.backend.order.dto;

import com.nbh.backend.order.entity.FulfilmentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CheckoutRequest(
        @NotBlank @Size(max = 200) String customerName,
        @NotBlank @Email String guestEmail,
        @NotBlank String guestPhone,
        @NotNull FulfilmentType fulfilmentType,
        String deliveryAddress,
        @Pattern(regexp = "BODA|VEHICLE", message = "deliveryMethod must be BODA or VEHICLE") String deliveryMethod
) {}
