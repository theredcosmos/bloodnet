package com.bloodnet.transfer.internal;

import com.bloodnet.transfer.TransferStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Append-only history of status changes; drives the progress steps in the UI. */
@Entity
@Table(name = "transfer_event")
@Getter
@Setter
@NoArgsConstructor
public class TransferEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long transferId;
    @Enumerated(EnumType.STRING)
    private TransferStatus fromStatus;
    @Enumerated(EnumType.STRING)
    private TransferStatus toStatus;
    private String actor;
    private Instant createdAt;
}
