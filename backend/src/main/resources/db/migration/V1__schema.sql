-- =====================================================================
-- BloodNet schema (MySQL 8, InnoDB, utf8mb4). All timestamps are UTC.
-- Ownership of each table by module is noted so boundaries stay clear.
-- =====================================================================

-- ---------------------------------------------------------------- inventory
CREATE TABLE blood_bank (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    code        VARCHAR(20)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    city        VARCHAR(80)  NOT NULL,
    address     VARCHAR(255),
    phone       VARCHAR(20),
    latitude    DOUBLE       NOT NULL,
    longitude   DOUBLE       NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_bank_code (code)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- request
CREATE TABLE hospital (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    name        VARCHAR(120) NOT NULL,
    city        VARCHAR(80)  NOT NULL,
    latitude    DOUBLE       NOT NULL,
    longitude   DOUBLE       NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- auth
CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    email         VARCHAR(160) NOT NULL,
    full_name     VARCHAR(120) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,           -- ADMIN | BANK_STAFF | HOSPITAL
    bank_id       BIGINT,                          -- set for BANK_STAFF
    hospital_id   BIGINT,                          -- set for HOSPITAL
    enabled       BIT(1)       NOT NULL DEFAULT 1,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_email (email),
    CONSTRAINT fk_user_bank FOREIGN KEY (bank_id) REFERENCES blood_bank (id),
    CONSTRAINT fk_user_hospital FOREIGN KEY (hospital_id) REFERENCES hospital (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- donor
CREATE TABLE donor (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    full_name        VARCHAR(120) NOT NULL,
    blood_group      VARCHAR(12)  NOT NULL,
    gender           VARCHAR(10)  NOT NULL,
    date_of_birth    DATE         NOT NULL,
    phone            VARCHAR(20)  NOT NULL,
    email            VARCHAR(160),
    registered_bank_id BIGINT     NOT NULL,
    last_donation_at DATETIME(6),
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_donor_phone (phone),
    KEY idx_donor_group (blood_group),
    CONSTRAINT fk_donor_bank FOREIGN KEY (registered_bank_id) REFERENCES blood_bank (id)
) ENGINE = InnoDB;

CREATE TABLE donation (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    donor_id      BIGINT      NOT NULL,
    bank_id       BIGINT      NOT NULL,
    collected_at  DATETIME(6) NOT NULL,
    volume_ml     INT         NOT NULL,
    recorded_by   VARCHAR(160) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_donation_donor (donor_id, collected_at),
    CONSTRAINT fk_donation_donor FOREIGN KEY (donor_id) REFERENCES donor (id),
    CONSTRAINT fk_donation_bank FOREIGN KEY (bank_id) REFERENCES blood_bank (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- inventory
CREATE TABLE blood_compatibility (
    recipient_group VARCHAR(12) NOT NULL,
    donor_group     VARCHAR(12) NOT NULL,
    component_type  VARCHAR(20) NOT NULL,
    PRIMARY KEY (recipient_group, donor_group, component_type)
) ENGINE = InnoDB;

CREATE TABLE blood_unit (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    unit_code       VARCHAR(40) NOT NULL,
    donation_id     BIGINT,
    bank_id         BIGINT      NOT NULL,
    blood_group     VARCHAR(12) NOT NULL,
    component_type  VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL,          -- AVAILABLE | RESERVED | ISSUED | IN_TRANSIT | EXPIRED | DISCARDED
    collected_at    DATETIME(6) NOT NULL,
    expires_at      DATETIME(6) NOT NULL,
    request_id      BIGINT,                        -- set while RESERVED/ISSUED
    reserved_until  DATETIME(6),
    transfer_id     BIGINT,                        -- set while IN_TRANSIT
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_unit_code (unit_code),
    -- The allocation query filters on these columns and orders by expires_at.
    KEY idx_unit_alloc (bank_id, component_type, status, blood_group, expires_at),
    KEY idx_unit_expiry (status, expires_at),
    KEY idx_unit_request (request_id),
    KEY idx_unit_transfer (transfer_id),
    CONSTRAINT fk_unit_bank FOREIGN KEY (bank_id) REFERENCES blood_bank (id),
    CONSTRAINT fk_unit_donation FOREIGN KEY (donation_id) REFERENCES donation (id)
) ENGINE = InnoDB;

CREATE TABLE rebalance_suggestion (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    unit_id        BIGINT      NOT NULL,
    from_bank_id   BIGINT      NOT NULL,
    to_bank_id     BIGINT      NOT NULL,
    distance_km    DOUBLE      NOT NULL,
    reason         VARCHAR(255) NOT NULL,
    status         VARCHAR(20) NOT NULL,           -- OPEN | ACTED | DISMISSED
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_suggestion_unit (unit_id, to_bank_id),
    CONSTRAINT fk_sugg_unit FOREIGN KEY (unit_id) REFERENCES blood_unit (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- request
CREATE TABLE blood_request (
    id               BIGINT      NOT NULL AUTO_INCREMENT,
    hospital_id      BIGINT      NOT NULL,
    bank_id          BIGINT      NOT NULL,
    blood_group      VARCHAR(12) NOT NULL,
    component_type   VARCHAR(20) NOT NULL,
    units_requested  INT         NOT NULL,
    units_allocated  INT         NOT NULL DEFAULT 0,
    urgency          VARCHAR(12) NOT NULL,         -- ROUTINE | URGENT | EMERGENCY
    status           VARCHAR(20) NOT NULL,         -- PENDING | PARTIALLY_RESERVED | RESERVED | ISSUED | CANCELLED
    patient_ref      VARCHAR(60) NOT NULL,
    notes            VARCHAR(255),
    reserved_until   DATETIME(6),
    created_by       VARCHAR(160) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_req_bank_status (bank_id, status),
    KEY idx_req_hospital (hospital_id, created_at),
    KEY idx_req_reserved (status, reserved_until),
    CONSTRAINT fk_req_hospital FOREIGN KEY (hospital_id) REFERENCES hospital (id),
    CONSTRAINT fk_req_bank FOREIGN KEY (bank_id) REFERENCES blood_bank (id)
) ENGINE = InnoDB;

-- History of every unit ever reserved for a request. A row is "active" while released_at IS NULL.
-- Invariant checked by the concurrency test: at most one active row per unit.
CREATE TABLE request_allocation (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    request_id    BIGINT      NOT NULL,
    unit_id       BIGINT      NOT NULL,
    allocated_at  DATETIME(6) NOT NULL,
    released_at   DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_alloc_request (request_id),
    KEY idx_alloc_unit (unit_id),
    CONSTRAINT fk_alloc_request FOREIGN KEY (request_id) REFERENCES blood_request (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- transfer
CREATE TABLE transfer (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    from_bank_id    BIGINT      NOT NULL,          -- bank that sends the units
    to_bank_id      BIGINT      NOT NULL,          -- bank that asked for them
    blood_group     VARCHAR(12) NOT NULL,
    component_type  VARCHAR(20) NOT NULL,
    units_count     INT         NOT NULL,
    status          VARCHAR(20) NOT NULL,          -- REQUESTED | APPROVED | DISPATCHED | RECEIVED | REJECTED | CANCELLED
    notes           VARCHAR(255),
    requested_by    VARCHAR(160) NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    updated_at      DATETIME(6) NOT NULL,
    version         BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_transfer_from (from_bank_id, status),
    KEY idx_transfer_to (to_bank_id, status),
    CONSTRAINT fk_transfer_from FOREIGN KEY (from_bank_id) REFERENCES blood_bank (id),
    CONSTRAINT fk_transfer_to FOREIGN KEY (to_bank_id) REFERENCES blood_bank (id)
) ENGINE = InnoDB;

CREATE TABLE transfer_event (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    transfer_id  BIGINT      NOT NULL,
    from_status  VARCHAR(20),
    to_status    VARCHAR(20) NOT NULL,
    actor        VARCHAR(160) NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_tevent_transfer (transfer_id),
    CONSTRAINT fk_tevent_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- notification
CREATE TABLE notification (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    bank_id     BIGINT,
    hospital_id BIGINT,
    type        VARCHAR(40)  NOT NULL,
    message     VARCHAR(500) NOT NULL,
    is_read     BIT(1)       NOT NULL DEFAULT 0,
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notif_bank (bank_id, is_read, created_at),
    KEY idx_notif_hospital (hospital_id, is_read, created_at)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- reporting
CREATE TABLE stock_summary (
    bank_id         BIGINT      NOT NULL,
    blood_group     VARCHAR(12) NOT NULL,
    component_type  VARCHAR(20) NOT NULL,
    available       INT         NOT NULL,
    reserved        INT         NOT NULL,
    expiring_48h    INT         NOT NULL,
    refreshed_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (bank_id, blood_group, component_type)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- shared
CREATE TABLE audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    actor       VARCHAR(160) NOT NULL,
    action      VARCHAR(80)  NOT NULL,
    target      VARCHAR(255),
    outcome     VARCHAR(20)  NOT NULL,             -- SUCCESS | FAILURE
    detail      VARCHAR(500),
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_created (created_at)
) ENGINE = InnoDB;

-- ---------------------------------------------------------------- infrastructure
-- ShedLock: one row per scheduled job; guarantees a job runs on a single instance at a time.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB;

-- Spring Modulith event publication registry (transactional outbox for async module listeners).
-- Upper-case name on purpose: Modulith's MySQL SQL uses EVENT_PUBLICATION and Linux MySQL table names are case-sensitive.
CREATE TABLE EVENT_PUBLICATION (
    id               VARCHAR(36)   NOT NULL,
    listener_id      VARCHAR(512)  NOT NULL,
    event_type       VARCHAR(512)  NOT NULL,
    serialized_event VARCHAR(4000) NOT NULL,
    publication_date TIMESTAMP(6)  NOT NULL,
    completion_date  TIMESTAMP(6)  NULL DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX event_publication_by_completion_date_idx (completion_date)
) ENGINE = InnoDB;
