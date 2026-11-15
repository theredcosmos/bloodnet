package com.bloodnet.notification.internal;

import com.bloodnet.shared.AuthUser;
import com.bloodnet.shared.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository repo;

    public record NotificationView(Long id, String type, String message, boolean read, Instant createdAt) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<NotificationView> mine() {
        AuthUser u = CurrentUser.get();
        PageRequest top = PageRequest.of(0, 50);
        List<Notification> list = u.bankId() != null ? repo.findByBankIdOrderByCreatedAtDesc(u.bankId(), top)
                : u.hospitalId() != null ? repo.findByHospitalIdOrderByCreatedAtDesc(u.hospitalId(), top)
                : repo.findAllByOrderByCreatedAtDesc(top);
        return list.stream().map(n -> new NotificationView(n.getId(), n.getType(), n.getMessage(), n.isRead(),
                n.getCreatedAt())).toList();
    }

    @GetMapping("/unread-count")
    @Transactional(readOnly = true)
    public Map<String, Long> unread() {
        AuthUser u = CurrentUser.get();
        long n = u.bankId() != null ? repo.countByBankIdAndReadFalse(u.bankId())
                : u.hospitalId() != null ? repo.countByHospitalIdAndReadFalse(u.hospitalId()) : 0;
        return Map.of("unread", n);
    }

    @PostMapping("/read-all")
    @Transactional
    public Map<String, Integer> readAll() {
        AuthUser u = CurrentUser.get();
        int n = u.bankId() != null ? repo.markAllReadForBank(u.bankId())
                : u.hospitalId() != null ? repo.markAllReadForHospital(u.hospitalId()) : 0;
        return Map.of("updated", n);
    }
}
