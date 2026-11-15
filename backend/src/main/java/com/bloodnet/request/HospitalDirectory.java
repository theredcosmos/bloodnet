package com.bloodnet.request;

import com.bloodnet.request.internal.HospitalRepository;
import com.bloodnet.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Public API: look up hospitals (used by auth to validate user assignments). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HospitalDirectory {

    private final HospitalRepository hospitals;

    public List<HospitalInfo> all() {
        return hospitals.findAll().stream()
                .map(h -> new HospitalInfo(h.getId(), h.getName(), h.getCity(), h.getLatitude(), h.getLongitude()))
                .toList();
    }

    public HospitalInfo get(Long id) {
        return hospitals.findById(id)
                .map(h -> new HospitalInfo(h.getId(), h.getName(), h.getCity(), h.getLatitude(), h.getLongitude()))
                .orElseThrow(() -> new NotFoundException("Hospital", id));
    }

    public boolean exists(Long id) {
        return id != null && hospitals.existsById(id);
    }

    public Optional<Long> findIdByName(String name) {
        return hospitals.findFirstByName(name).map(h -> h.getId());
    }
}
