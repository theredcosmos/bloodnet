package com.bloodnet.request.internal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Which unit was reserved for which request, and when it was released (null = still held/issued). */
@Entity
@Table(name = "request_allocation")
@Getter
@Setter
@NoArgsConstructor
public class RequestAllocation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long requestId;
    private Long unitId;
    private Instant allocatedAt;
    private Instant releasedAt;

    RequestAllocation(Long requestId, Long unitId, Instant allocatedAt) {
        this.requestId = requestId;
        this.unitId = unitId;
        this.allocatedAt = allocatedAt;
    }
}
