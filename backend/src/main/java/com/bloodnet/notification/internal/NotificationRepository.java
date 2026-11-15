package com.bloodnet.notification.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByBankIdOrderByCreatedAtDesc(Long bankId, Pageable pageable);

    List<Notification> findByHospitalIdOrderByCreatedAtDesc(Long hospitalId, Pageable pageable);

    List<Notification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByBankIdAndReadFalse(Long bankId);

    long countByHospitalIdAndReadFalse(Long hospitalId);

    @Modifying
    @Query("update Notification n set n.read = true where n.bankId = :bankId and n.read = false")
    int markAllReadForBank(@Param("bankId") Long bankId);

    @Modifying
    @Query("update Notification n set n.read = true where n.hospitalId = :hospitalId and n.read = false")
    int markAllReadForHospital(@Param("hospitalId") Long hospitalId);
}
