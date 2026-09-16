package com.nbh.backend.staff.entity;

import com.nbh.backend.branch.entity.Branch;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The dimension Moments' RBAC lacks: permissions answer *what*, this answers
 * *where*. A Branch-A cashier holding POS_SELL can sell only from Branch A's
 * locations; ANALYTICS_VIEW at branch scope shows one branch's numbers.
 */
@Getter
@Setter
@Entity
@Table(name = "staff_branch_assignment")
@IdClass(StaffBranchAssignment.Id.class)
public class StaffBranchAssignment {

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private StaffUser user;

    @jakarta.persistence.Id
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private Branch branch;

    @Column(nullable = false)
    private boolean isPrimary = false;

    @Getter
    @Setter
    public static class Id implements Serializable {
        private UUID user;
        private UUID branch;

        public Id() {}

        public Id(UUID user, UUID branch) {
            this.user = user;
            this.branch = branch;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return Objects.equals(user, id.user) && Objects.equals(branch, id.branch);
        }

        @Override
        public int hashCode() {
            return Objects.hash(user, branch);
        }
    }
}
