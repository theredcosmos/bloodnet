package com.bloodnet.request.internal;

import com.bloodnet.inventory.BankDirectory;
import com.bloodnet.inventory.BankInfo;
import com.bloodnet.request.HospitalDirectory;
import com.bloodnet.request.HospitalInfo;
import com.bloodnet.request.RequestStatus;
import com.bloodnet.request.Urgency;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.CurrentUser;
import com.bloodnet.shared.NotFoundException;
import com.bloodnet.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class RequestController {

    private final RequestService requests;
    private final AllocationService allocation;
    private final HospitalDirectory hospitals;
    private final BankDirectory banks;

    public record CreateRequest(@NotNull Long bankId, @NotNull BloodGroup bloodGroup,
                                @NotNull ComponentType componentType, @Min(1) @Max(20) int units,
                                @NotNull Urgency urgency, @NotBlank @Size(max = 60) String patientRef,
                                @Size(max = 255) String notes) {
    }

    public record RequestView(Long id, Long hospitalId, String hospitalName, Long bankId, String bankName,
                              BloodGroup bloodGroup, ComponentType componentType, int unitsRequested,
                              int unitsAllocated, Urgency urgency, RequestStatus status, String patientRef,
                              String notes, Instant reservedUntil, String createdBy, Instant createdAt) {
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('HOSPITAL')")
    public RequestView create(@Valid @RequestBody CreateRequest req) {
        return view(requests.create(new RequestService.NewRequest(req.bankId(), req.bloodGroup(), req.componentType(),
                req.units(), req.urgency(), req.patientRef(), req.notes())));
    }

    @GetMapping("/requests")
    public PageResponse<RequestView> list(@RequestParam(required = false) Long bankId,
                                          @RequestParam(required = false) List<RequestStatus> status,
                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        Map<Long, String> bankNames = bankNames();
        Map<Long, String> hospitalNames = hospitalNames();
        return PageResponse.of(requests.search(bankId, status,
                        PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")))),
                r -> view(r, bankNames, hospitalNames));
    }

    @GetMapping("/requests/{id}")
    public RequestView get(@PathVariable Long id) {
        return view(requests.get(id));
    }

    @GetMapping("/requests/{id}/units")
    public List<Long> units(@PathVariable Long id) {
        return requests.activeUnitIds(id);
    }

    @PostMapping("/requests/{id}/allocate")
    @PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
    public AllocationService.AllocationResult allocate(@PathVariable Long id) {
        return allocation.allocate(id);
    }

    @PostMapping("/requests/{id}/issue")
    @PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
    public RequestView issue(@PathVariable Long id) {
        return view(allocation.issue(id));
    }

    @PostMapping("/requests/{id}/cancel")
    public RequestView cancel(@PathVariable Long id) {
        return view(requests.cancel(id));
    }

    @GetMapping("/hospitals")
    public List<HospitalInfo> hospitalsList() {
        return hospitals.all();
    }

    @GetMapping("/hospitals/me")
    @PreAuthorize("hasRole('HOSPITAL')")
    public HospitalInfo myHospital() {
        Long id = CurrentUser.get().hospitalId();
        if (id == null) throw new NotFoundException("Hospital for user", CurrentUser.actor());
        return hospitals.get(id);
    }

    private RequestView view(BloodRequest r) {
        return view(r, bankNames(), hospitalNames());
    }

    private RequestView view(BloodRequest r, Map<Long, String> bankNames, Map<Long, String> hospitalNames) {
        return new RequestView(r.getId(), r.getHospitalId(), hospitalNames.get(r.getHospitalId()), r.getBankId(),
                bankNames.get(r.getBankId()), r.getBloodGroup(), r.getComponentType(), r.getUnitsRequested(),
                r.getUnitsAllocated(), r.getUrgency(), r.getStatus(), r.getPatientRef(), r.getNotes(),
                r.getReservedUntil(), r.getCreatedBy(), r.getCreatedAt());
    }

    private Map<Long, String> bankNames() {
        return banks.all().stream().collect(Collectors.toMap(BankInfo::id, BankInfo::name));
    }

    private Map<Long, String> hospitalNames() {
        return hospitals.all().stream().collect(Collectors.toMap(HospitalInfo::id, HospitalInfo::name, (a, b) -> a));
    }
}
