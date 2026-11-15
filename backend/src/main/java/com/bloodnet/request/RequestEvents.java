package com.bloodnet.request;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;

/** Events published by the request module (consumed asynchronously by notifications). */
public final class RequestEvents {
    private RequestEvents() {
    }

    public record RequestCreated(Long requestId, Long hospitalId, Long bankId, BloodGroup bloodGroup,
                                 ComponentType componentType, int units, Urgency urgency) {
    }

    public record RequestReserved(Long requestId, Long hospitalId, Long bankId, int allocated, int requested) {
    }

    public record RequestIssued(Long requestId, Long hospitalId, Long bankId, int units) {
    }

    public record RequestCancelled(Long requestId, Long hospitalId, Long bankId) {
    }

    public record ReservationExpired(Long requestId, Long hospitalId, Long bankId, int releasedUnits) {
    }
}
