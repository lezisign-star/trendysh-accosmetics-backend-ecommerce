package com.nbh.backend.branch.controller;

import com.nbh.backend.branch.dto.BranchDto;
import com.nbh.backend.branch.dto.StockLocationDto;
import com.nbh.backend.branch.entity.Branch;
import com.nbh.backend.branch.repository.BranchRepository;
import com.nbh.backend.branch.repository.StockLocationRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
        return branchRepository.findAll().stream().map(BranchDto::from).toList();
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
        branch.setName(request.name());
        branch.setCity(request.city());
        branch.setActive(true);
        return BranchDto.from(branchRepository.save(branch));
    }

    private Branch findBranchOrThrow(UUID id) {
        return branchRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("No branch: " + id));
    }

    public record CreateBranchRequest(
            @NotBlank String code,
            @NotBlank String name,
            String city
    ) {}
}
