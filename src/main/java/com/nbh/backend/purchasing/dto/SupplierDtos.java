package com.nbh.backend.purchasing.dto;

import com.nbh.backend.purchasing.entity.Supplier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public final class SupplierDtos {

    private SupplierDtos() {}

    public record SupplierRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 150) String contactName,
            @Size(max = 30) String phone,
            @Size(max = 255) String email,
            @Size(max = 500) String address,
            @Size(max = 1000) String notes) {}

    public record SupplierDto(
            UUID id, String name, String contactName, String phone, String email, String address, String notes) {

        public static SupplierDto from(Supplier s) {
            return new SupplierDto(s.getId(), s.getName(), s.getContactName(), s.getPhone(), s.getEmail(),
                    s.getAddress(), s.getNotes());
        }
    }
}
