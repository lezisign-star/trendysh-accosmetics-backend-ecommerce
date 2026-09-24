package com.nbh.backend.branch.controller;

import com.nbh.backend.branch.dto.BranchDto;
import com.nbh.backend.branch.dto.StockLocationDto;
import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches")
public class BranchController {

    private final BranchRepository branchRepository;
    private final StockLocationRepository locationRepository;

    public BranchController(BranchRepository branchRepository, StockLocationRepository locationRepository) {
        this.branchRepository = branchRepository;
        this.locationRepository = locationRepository;
    }

    /** Any authenticated staff member can see the branch list — it's not sensitive. */
    @GetMapping
    @Transactional(readOnly = true)
    public List<BranchDto> list() {
        return branchRepository.findAllByOrderByName().stream().map(BranchDto::from).toList();
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public BranchDto get(@PathVariable UUID id) {
        return BranchDto.from(findBranchOrThrow(id));
    }

    @GetMapping("/{id}/locations")
    @Transactional(readOnly = true)
    public List<StockLocationDto> locations(@PathVariable UUID id) {
        findBranchOrThrow(id); // 404s if the branch doesn't exist
        return locationRepository.findByBranchId(id).stream().map(StockLocationDto::from).toList();
    }

    @PreAuthorize("hasAuthority('PERM_BRANCH_MANAGE')")
    @PostMapping
    @Transactional
    public BranchDto create(@Valid @RequestBody CreateBranchRequest request) {
        Branch branch = new Branch();
        branch.setCode(request.code());
        branch.setActive(true);
        applyFields(branch, request.name(), request.addressLine1(), request.addressLine2(),
                request.city(), request.phone(), request.email());
        return BranchDto.from(branchRepository.save(branch));
    }

    /**
     * Full-replace update. Setting {@code isDefaultOnlineFulfilment} true here atomically clears it
     * off whichever branch currently holds it — the DB's partial unique index only allows one, and
     * this is the one place in the whole app that's allowed to flip it (see {@code CheckoutService},
     * which trusts there's always exactly one main branch and would 500 if that were ever untrue).
     */
    @PreAuthorize("hasAuthority('PERM_BRANCH_MANAGE')")
    @PatchMapping("/{id}")
    @Transactional
    public BranchDto update(@PathVariable UUID id, @Valid @RequestBody UpdateBranchRequest request) {
        Branch branch = findBranchOrThrow(id);
        applyFields(branch, request.name(), request.addressLine1(), request.addressLine2(),
                request.city(), request.phone(), request.email());
        branch.setActive(request.isActive());

        if (request.isDefaultOnlineFulfilment() && !branch.isDefaultOnlineFulfilment()) {
            branchRepository.findByIsDefaultOnlineFulfilmentTrue().ifPresent(current -> {
                current.setDefaultOnlineFulfilment(false);
                branchRepository.save(current);
            });
        }
        branch.setDefaultOnlineFulfilment(request.isDefaultOnlineFulfilment());

        return BranchDto.from(branchRepository.save(branch));
    }

    private void applyFields(Branch branch, String name, String addressLine1, String addressLine2,
                              String city, String phone, String email) {
        branch.setName(name);
        branch.setAddressLine1(blankToNull(addressLine1));
        branch.setAddressLine2(blankToNull(addressLine2));
        branch.setCity(blankToNull(city));
        branch.setPhone(blankToNull(phone));
        branch.setEmail(blankToNull(email));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Branch findBranchOrThrow(UUID id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No branch: " + id));
    }

    public record CreateBranchRequest(
            @NotBlank String code,
            @NotBlank String name,
            String addressLine1,
            String addressLine2,
            String city,
            String phone,
            String email
    ) {}

    public record UpdateBranchRequest(
            @NotBlank String name,
            String addressLine1,
            String addressLine2,
            String city,
            String phone,
            String email,
            @NotNull Boolean isActive,
            @NotNull Boolean isDefaultOnlineFulfilment
    ) {}
}
