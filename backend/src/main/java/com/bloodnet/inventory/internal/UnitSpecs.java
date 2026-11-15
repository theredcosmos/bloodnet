package com.bloodnet.inventory.internal;

import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.UnitStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;

/** Composable optional filters for the inventory list (null filter = not applied). */
final class UnitSpecs {
    private UnitSpecs() {
    }

    static Specification<BloodUnit> bank(Long bankId) {
        return (r, q, cb) -> bankId == null ? null : cb.equal(r.get("bankId"), bankId);
    }

    static Specification<BloodUnit> group(BloodGroup g) {
        return (r, q, cb) -> g == null ? null : cb.equal(r.get("bloodGroup"), g);
    }

    static Specification<BloodUnit> component(ComponentType c) {
        return (r, q, cb) -> c == null ? null : cb.equal(r.get("componentType"), c);
    }

    static Specification<BloodUnit> status(UnitStatus s) {
        return (r, q, cb) -> s == null ? null : cb.equal(r.get("status"), s);
    }

    static Specification<BloodUnit> expiresBefore(Instant t) {
        return (r, q, cb) -> t == null ? null : cb.lessThanOrEqualTo(r.get("expiresAt"), t);
    }

    static Specification<BloodUnit> codeLike(String code) {
        return (r, q, cb) -> code == null || code.isBlank() ? null
                : cb.like(cb.upper(r.get("unitCode")), "%" + code.trim().toUpperCase() + "%");
    }
}
