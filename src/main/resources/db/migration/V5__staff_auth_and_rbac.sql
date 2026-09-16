-- Named role entity, not an enum — mirrors Moments Packaging's StaffRole shape
-- (J:\backend\moments-packaging-backend-Java-First client\...\user\entity\StaffRole.java),
-- which the design doc recommends adopting almost wholesale, plus the branch-scope
-- dimension Moments lacks (staff_branch_assignment below).
CREATE TABLE staff_role (
    id           UUID PRIMARY KEY,
    name         VARCHAR(50) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL,
    description  TEXT,
    is_default   BOOLEAN NOT NULL DEFAULT false,
    deleted      BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE staff_role_permissions (
    role_id    UUID NOT NULL REFERENCES staff_role(id),
    permission VARCHAR(50) NOT NULL,
    PRIMARY KEY (role_id, permission)
);

CREATE TABLE staff_user (
    id                      UUID PRIMARY KEY,
    email                   VARCHAR(255) NOT NULL UNIQUE,
    password                VARCHAR(255) NOT NULL,
    first_name              VARCHAR(100) NOT NULL,
    last_name               VARCHAR(100) NOT NULL,
    enabled                 BOOLEAN NOT NULL DEFAULT true,
    staff_role_id           UUID REFERENCES staff_role(id),
    must_change_password    BOOLEAN NOT NULL DEFAULT false,
    temp_password_expires_at TIMESTAMPTZ,
    deleted                 BOOLEAN NOT NULL DEFAULT false,
    deleted_at              TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_staff_user_staff_role ON staff_user (staff_role_id);

-- The dimension Moments lacks: permissions answer *what*, this answers *where*.
-- A Branch-A cashier holding POS_SELL can sell only from Branch A's locations.
CREATE TABLE staff_branch_assignment (
    user_id    UUID NOT NULL REFERENCES staff_user(id),
    branch_id  UUID NOT NULL REFERENCES branch(id),
    is_primary BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (user_id, branch_id)
);
