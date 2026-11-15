package com.bloodnet.request.internal;

import com.bloodnet.request.RequestStatus;
import com.bloodnet.request.Urgency;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.ConflictException;
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

/** A hospital's request for N units of one group/component from one bank. State changes go through methods. */
@Entity
@Table(name = "blood_request")
@Getter
@Setter
@NoArgsConstructor
public class BloodRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long hospitalId;
    private Long bankId;
    @Enumerated(EnumType.STRING)
    private BloodGroup bloodGroup;
    @Enumerated(EnumType.STRING)
    private ComponentType componentType;
    private int unitsRequested;
    @Setter(AccessLevel.NONE)
    private int unitsAllocated;
    @Enumerated(EnumType.STRING)
    private Urgency urgency;
    @Enumerated(EnumType.STRING)
    @Setter(AccessLevel.NONE)
    private RequestStatus status;
    private String patientRef;
    private String notes;
    @Setter(AccessLevel.NONE)
    private Instant reservedUntil;
    private String createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version
    private Long version;

    void open(Instant now) {
        status = RequestStatus.PENDING;
        unitsAllocated = 0;
        createdAt = now;
        updatedAt = now;
    }

    int remaining() {
        return unitsRequested - unitsAllocated;
    }

    void addReserved(int units, Instant until, Instant now) {
        if (!status.canAllocate()) {
            throw new ConflictException("Request " + id + " is " + status + " and cannot be allocated");
        }
        if (units == 0) return;
        unitsAllocated += units;
        status = unitsAllocated >= unitsRequested ? RequestStatus.RESERVED : RequestStatus.PARTIALLY_RESERVED;
        reservedUntil = until;
        updatedAt = now;
    }

    void markIssued(Instant now) {
        if (!status.holdsUnits()) {
            throw new ConflictException("Request " + id + " is " + status + "; only reserved requests can be issued");
        }
        status = RequestStatus.ISSUED;
        reservedUntil = null;
        updatedAt = now;
    }

    /** Reservation timed out: units go back to the shelf and the request waits for a new allocation. */
    void resetReservation(Instant now) {
        status = RequestStatus.PENDING;
        unitsAllocated = 0;
        reservedUntil = null;
        updatedAt = now;
    }

    void cancel(Instant now) {
        if (status == RequestStatus.ISSUED || status == RequestStatus.CANCELLED) {
            throw new ConflictException("Request " + id + " is already " + status);
        }
        status = RequestStatus.CANCELLED;
        reservedUntil = null;
        updatedAt = now;
    }
}
