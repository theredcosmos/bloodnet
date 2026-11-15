package com.bloodnet.request;

import com.bloodnet.request.internal.BloodRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.Map;

/** Public read API for the dashboard. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RequestQueries {

    private final BloodRequestRepository requests;

    public Map<RequestStatus, Long> countsByStatus(Long bankId) {
        Map<RequestStatus, Long> m = new EnumMap<>(RequestStatus.class);
        for (RequestStatus s : RequestStatus.values()) m.put(s, 0L);
        (bankId == null ? requests.countAllByStatus() : requests.countByStatusForBank(bankId))
                .forEach(row -> m.put((RequestStatus) row[0], (Long) row[1]));
        return m;
    }
}
