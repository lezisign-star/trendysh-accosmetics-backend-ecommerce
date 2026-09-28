package com.nbh.backend.order.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record AssignTransporterRequest(
        @NotBlank @Size(max = 200) String transporterName,
        @NotBlank @Size(max = 20) String transporterPhone,
        @NotNull @DecimalMin("0") BigDecimal agreedPrice
) {}
