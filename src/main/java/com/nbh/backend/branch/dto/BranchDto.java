package com.nbh.backend.branch.dto;

import com.nbh.backend.branch.entity.Branch;

import java.util.UUID;

public record BranchDto(
        UUID id,
        String code,
        String name,
        String addressLine1,
        String addressLine2,
        String city,
        String phone,
        String email,
        boolean isActive,
        boolean isDefaultOnlineFulfilment
) {
    public static BranchDto from(Branch branch) {
        return new BranchDto(
                branch.getId(), branch.getCode(), branch.getName(), branch.getAddressLine1(),
                branch.getAddressLine2(), branch.getCity(), branch.getPhone(), branch.getEmail(),
                branch.isActive(), branch.isDefaultOnlineFulfilment());
    }
}
