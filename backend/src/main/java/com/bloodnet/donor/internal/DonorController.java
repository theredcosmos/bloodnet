package com.bloodnet.donor.internal;

import com.bloodnet.inventory.UnitView;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/donors")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
public class DonorController {

    private final DonorService service;
    private final Clock clock;

    public record RegisterDonorRequest(@NotBlank @Size(max = 120) String fullName,
                                       @NotNull BloodGroup bloodGroup,
                                       @NotBlank @Pattern(regexp = "MALE|FEMALE|OTHER") String gender,
                                       @NotNull @Past LocalDate dateOfBirth,
                                       @NotBlank @Pattern(regexp = "\\+?[0-9]{10,15}", message = "must be 10-15 digits") String phone,
                                       @Email @Size(max = 160) String email,
                                       Long bankId) {
    }

    public record RecordDonationRequest(Instant collectedAt,
                                        @Min(250) @Max(550) int volumeMl,
                                        List<ComponentType> components,
                                        Long bankId) {
    }

    public record DonorView(Long id, String fullName, BloodGroup bloodGroup, String gender, LocalDate dateOfBirth,
                            String phone, String email, Long registeredBankId, Instant lastDonationAt,
                            Instant nextEligibleAt, boolean eligibleNow) {
    }

    public record DonationView(Long id, Long donorId, Long bankId, Instant collectedAt, int volumeMl,
                               String recordedBy, List<UnitView> units) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DonorView register(@Valid @RequestBody RegisterDonorRequest req) {
        return view(service.register(new DonorService.NewDonor(req.fullName(), req.bloodGroup(), req.gender(),
                req.dateOfBirth(), req.phone(), req.email(), req.bankId())));
    }

    @GetMapping
    public PageResponse<DonorView> search(@RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(service.search(q, PageRequest.of(page, size, Sort.by("fullName").and(Sort.by("id")))),
                this::view);
    }

    @GetMapping("/{id}")
    public DonorView get(@PathVariable Long id) {
        return view(service.get(id));
    }

    @GetMapping("/{id}/donations")
    public List<DonationView> history(@PathVariable Long id) {
        return service.history(id).stream().map(d -> new DonationView(d.getId(), d.getDonorId(), d.getBankId(),
                d.getCollectedAt(), d.getVolumeMl(), d.getRecordedBy(), List.of())).toList();
    }

    @PostMapping("/{id}/donations")
    @ResponseStatus(HttpStatus.CREATED)
    public DonationView recordDonation(@PathVariable Long id, @Valid @RequestBody RecordDonationRequest req) {
        DonorService.DonationResult r = service.recordDonation(id,
                new DonorService.NewDonation(req.collectedAt(), req.volumeMl(), req.components(), req.bankId()));
        var d = r.donation();
        return new DonationView(d.getId(), d.getDonorId(), d.getBankId(), d.getCollectedAt(), d.getVolumeMl(),
                d.getRecordedBy(), r.units());
    }

    private DonorView view(Donor d) {
        Instant now = clock.instant();
        boolean eligible = EligibilityPolicy.whyIneligible(d.getDateOfBirth(), d.getLastDonationAt(), now).isEmpty();
        return new DonorView(d.getId(), d.getFullName(), d.getBloodGroup(), d.getGender(), d.getDateOfBirth(),
                d.getPhone(), d.getEmail(), d.getRegisteredBankId(), d.getLastDonationAt(),
                EligibilityPolicy.nextEligibleAt(d.getLastDonationAt()), eligible);
    }
}
