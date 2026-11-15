package com.bloodnet.transfer.internal;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.ConflictException;
import com.bloodnet.transfer.TransferStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "transfer")
@Getter
@Setter
@NoArgsConstructor
public class Transfer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long fromBankId;
    private Long toBankId;
    @Enumerated(EnumType.STRING)
    private BloodGroup bloodGroup;
    @Enumerated(EnumType.STRING)
    private ComponentType componentType;
    private int unitsCount;
    @Enumerated(EnumType.STRING)
    @Setter(AccessLevel.NONE)
    private TransferStatus status;
    private String notes;
    private String requestedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    void start(Instant now) {
        this.status = TransferStatus.REQUESTED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** The only way to change status: guarded by the state machine on the enum. */
    TransferStatus moveTo(TransferStatus target, Instant now) {
        if (!status.canMoveTo(target)) {
            throw new ConflictException("Transfer " + id + " cannot go from " + status + " to " + target);
        }
        TransferStatus previous = status;
        this.status = target;
        this.updatedAt = now;
        return previous;
    }
}
