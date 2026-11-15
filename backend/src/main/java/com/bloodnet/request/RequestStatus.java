package com.bloodnet.request;

public enum RequestStatus {
    PENDING,             // waiting for allocation
    PARTIALLY_RESERVED,  // some units held, more needed
    RESERVED,            // all units held until reserved_until
    ISSUED,              // units handed over (terminal)
    CANCELLED;           // terminal

    public boolean canAllocate() {
        return this == PENDING || this == PARTIALLY_RESERVED;
    }

    public boolean holdsUnits() {
        return this == PARTIALLY_RESERVED || this == RESERVED;
    }
}
