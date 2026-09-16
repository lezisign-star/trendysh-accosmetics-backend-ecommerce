package com.nbh.backend.branch.dto;

import com.nbh.backend.branch.entity.Branch;

import java.util.UUID;

public record BranchDto(
        UUID id,
        String code,
        String name,
        String city,
        boolean isActive,
        boolean isDefaultOnlineFulfilment
) {
    public static BranchDto from(Branch branch) {
        return new BranchDto(
                branch.getId(), branch.getCode(), branch.getName(), branch.getCity(),
                branch.isActive(), branch.isDefaultOnlineFulfilment());
    }
}
