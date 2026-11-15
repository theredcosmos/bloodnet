package com.bloodnet.request.internal;

import com.bloodnet.inventory.BankDirectory;
import com.bloodnet.inventory.UnitAllocator;
import com.bloodnet.request.RequestEvents.RequestCancelled;
import com.bloodnet.request.RequestEvents.RequestCreated;
import com.bloodnet.request.RequestStatus;
import com.bloodnet.request.Urgency;
import com.bloodnet.shared.AuthUser;
import com.bloodnet.shared.Audited;
import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.CurrentUser;
import com.bloodnet.shared.ForbiddenOperationException;
import com.bloodnet.shared.NotFoundException;
import com.bloodnet.shared.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestService {

    private final BloodRequestRepository requests;
    private final RequestAllocationRepository allocations;
    private final BankDirectory banks;
    private final UnitAllocator unitAllocator;
    private final BankAccess bankAccess;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public record NewRequest(Long bankId, BloodGroup bloodGroup, ComponentType componentType, int units,
                             Urgency urgency, String patientRef, String notes) {
    }

    @Audited("CREATE_REQUEST")
    @Transactional
    public BloodRequest create(NewRequest cmd) {
        AuthUser u = CurrentUser.get();
        if (u.role() != Role.HOSPITAL || u.hospitalId() == null) {
            throw new ForbiddenOperationException("Only hospital users can raise requests");
        }
        if (!banks.exists(cmd.bankId())) {
            throw new BusinessRuleException("Unknown blood bank " + cmd.bankId());
        }
        Instant now = clock.instant();
        BloodRequest r = new BloodRequest();
        r.setHospitalId(u.hospitalId());
        r.setBankId(cmd.bankId());
        r.setBloodGroup(cmd.bloodGroup());
        r.setComponentType(cmd.componentType());
        r.setUnitsRequested(cmd.units());
        r.setUrgency(cmd.urgency());
        r.setPatientRef(cmd.patientRef());
        r.setNotes(cmd.notes());
        r.setCreatedBy(u.email());
        r.open(now);
        requests.save(r);
        events.publishEvent(new RequestCreated(r.getId(), r.getHospitalId(), r.getBankId(), r.getBloodGroup(),
                r.getComponentType(), r.getUnitsRequested(), r.getUrgency()));
        return r;
    }

    @Audited("CANCEL_REQUEST")
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BloodRequest cancel(Long id) {
        BloodRequest r = requests.findForUpdate(id).orElseThrow(() -> new NotFoundException("Request", id));
        checkVisible(r);
        Instant now = clock.instant();
        if (r.getStatus().holdsUnits()) {
            unitAllocator.releaseForRequest(r.getId());
            allocations.releaseAll(r.getId(), now);
        }
        r.cancel(now);
        events.publishEvent(new RequestCancelled(r.getId(), r.getHospitalId(), r.getBankId()));
        return r;
    }

    @Transactional(readOnly = true)
    public BloodRequest get(Long id) {
        BloodRequest r = requests.findById(id).orElseThrow(() -> new NotFoundException("Request", id));
        checkVisible(r);
        return r;
    }

    @Transactional(readOnly = true)
    public List<Long> activeUnitIds(Long requestId) {
        get(requestId);
        return allocations.findByRequestIdAndReleasedAtIsNull(requestId).stream().map(RequestAllocation::getUnitId).toList();
    }

    /** Hospitals see their own requests; staff see their bank's; admins see all (optionally filtered). */
    @Transactional(readOnly = true)
    public Page<BloodRequest> search(Long bankId, List<RequestStatus> statuses, Pageable pageable) {
        AuthUser u = CurrentUser.get();
        Specification<BloodRequest> spec = (root, q, cb) -> null;
        if (u.role() == Role.HOSPITAL) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("hospitalId"), u.hospitalId()));
        } else {
            Long scoped = u.isAdmin() ? bankId : bankAccess.resolveBank(bankId);
            if (scoped != null) {
                spec = spec.and((root, q, cb) -> cb.equal(root.get("bankId"), scoped));
            }
        }
        if (statuses != null && !statuses.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("status").in(statuses));
        }
        return requests.findAll(spec, pageable);
    }

    private void checkVisible(BloodRequest r) {
        AuthUser u = CurrentUser.get();
        boolean ok = u.isAdmin()
                || (u.role() == Role.HOSPITAL && r.getHospitalId().equals(u.hospitalId()))
                || (u.role() == Role.BANK_STAFF && r.getBankId().equals(u.bankId()));
        if (!ok) {
            throw new ForbiddenOperationException("Not your request");
        }
    }
}
