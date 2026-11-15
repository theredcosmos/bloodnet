package com.bloodnet.inventory.internal;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.UnitStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One physical bag of a single component. Bulk state changes (reserve/release/issue/expire)
 * are done with set-based SQL in {@link UnitSql}; this entity is used for reads and single-unit edits.
 * {@code @Version} gives optimistic locking for those single-unit edits.
 */
@Entity
@Table(name = "blood_unit")
@Getter
@Setter
@NoArgsConstructor
public class BloodUnit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String unitCode;
    private Long donationId;
    private Long bankId;
    @Enumerated(EnumType.STRING)
    private BloodGroup bloodGroup;
    @Enumerated(EnumType.STRING)
    private ComponentType componentType;
    @Enumerated(EnumType.STRING)
    private UnitStatus status;
    private Instant collectedAt;
    private Instant expiresAt;
    private Long requestId;
    private Instant reservedUntil;
    private Long transferId;
    @Version
    private Long version;
    private Instant createdAt;
    private Instant updatedAt;
}
