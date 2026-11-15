package com.bloodnet.shared;

import java.time.Duration;
import java.time.Instant;

/** Blood components and how long each one keeps. Expiry = collection time + shelf life. */
public enum ComponentType {
    WHOLE_BLOOD(Duration.ofDays(35)),
    RED_CELLS(Duration.ofDays(42)),
    PLATELETS(Duration.ofDays(5)),
    PLASMA(Duration.ofDays(365));

    private final Duration shelfLife;

    ComponentType(Duration shelfLife) {
        this.shelfLife = shelfLife;
    }

    public Duration shelfLife() {
        return shelfLife;
    }

    public Instant expiryFrom(Instant collectedAt) {
        return collectedAt.plus(shelfLife);
    }

    /** Components produced when a whole-blood donation is separated. */
    public static ComponentType[] separatedComponents() {
        return new ComponentType[]{RED_CELLS, PLATELETS, PLASMA};
    }
}
