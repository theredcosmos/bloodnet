package com.bloodnet.inventory.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface BloodUnitRepository extends JpaRepository<BloodUnit, Long>, JpaSpecificationExecutor<BloodUnit> {
}
