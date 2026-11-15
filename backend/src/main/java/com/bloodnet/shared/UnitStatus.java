package com.bloodnet.shared;

/** Lifecycle of one physical blood unit. */
public enum UnitStatus {
    AVAILABLE,   // on the shelf, can be reserved
    RESERVED,    // held for a hospital request until reserved_until
    ISSUED,      // handed to the hospital (terminal)
    IN_TRANSIT,  // dispatched to another bank
    EXPIRED,     // past expires_at (terminal, counts as wastage)
    DISCARDED    // removed for quality reasons (terminal, counts as wastage)
}
