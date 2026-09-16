package com.nbh.backend.staff.service;

import com.nbh.backend.staff.entity.StaffUser;
import com.nbh.backend.staff.repository.StaffBranchAssignmentRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Audit finding F12 (session 3): {@code staff_branch_assignment} existed in
 * the schema (`V5`) and was mapped (`StaffBranchAssignment`) but nothing ever
 * read it — a Branch-A cashier could receive, adjust, and reserve against
 * Branch B's locations. The architecture doc's §4.8 is explicit about this:
 * "permissions answer *what*; the assignment answers *where*."
 *
 * A staff account with NO assignment rows at all is treated as
 * branch-unrestricted — this is the bootstrap SUPER_ADMIN's situation (no
 * assignment is ever created for it) and matches the doc's framing of
 * assignment as an added restriction, not a prerequisite for every account.
 * The moment a staff account has at least one assignment, it is confined to
 * exactly those branches for every stock-affecting action.
 */
@Service
public class StaffBranchAccessService {

    private final StaffBranchAssignmentRepository assignmentRepository;

    public StaffBranchAccessService(StaffBranchAssignmentRepository assignmentRepository) {
        this.assignmentRepository = assignmentRepository;
    }

    /**
     * @throws AccessDeniedException if {@code staff} has at least one branch
     *         assignment and {@code branchId} is not among them. A null
     *         {@code branchId} (a virtual/global location) is always allowed —
     *         branch scoping only applies to a specific physical branch.
     */
    public void assertCanAccessBranch(StaffUser staff, UUID branchId) {
        if (branchId == null) {
            return;
        }
        List<UUID> assignedBranchIds = assignmentRepository.findByUserId(staff.getId()).stream()
                .map(a -> a.getBranch().getId())
                .toList();
        if (assignedBranchIds.isEmpty()) {
            return; // unrestricted — no assignment recorded for this staff account
        }
        if (!assignedBranchIds.contains(branchId)) {
            throw new AccessDeniedException(
                    "Staff account " + staff.getEmail() + " is not assigned to branch " + branchId);
        }
    }
}
