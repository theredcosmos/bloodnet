package com.bloodnet.donor.internal;

import com.bloodnet.inventory.UnitIntake;
import com.bloodnet.inventory.UnitView;
import com.bloodnet.shared.Audited;
import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.ConflictException;
import com.bloodnet.shared.CurrentUser;
import com.bloodnet.shared.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DonorService {

    private final DonorRepository donors;
    private final DonationRepository donations;
    private final UnitIntake unitIntake;
    private final BankAccess bankAccess;
    private final Clock clock;

    public record NewDonor(String fullName, BloodGroup bloodGroup, String gender, LocalDate dateOfBirth,
                           String phone, String email, Long bankId) {
    }

    public record NewDonation(Instant collectedAt, int volumeMl, List<ComponentType> components, Long bankId) {
    }

    public record DonationResult(Donation donation, List<UnitView> units) {
    }

    @Audited("REGISTER_DONOR")
    @Transactional
    public Donor register(NewDonor cmd) {
        Long bankId = bankAccess.resolveBank(cmd.bankId());
        if (donors.existsByPhone(cmd.phone())) {
            throw new ConflictException("A donor with phone " + cmd.phone() + " already exists");
        }
        Donor d = new Donor();
        d.setFullName(cmd.fullName().trim());
        d.setBloodGroup(cmd.bloodGroup());
        d.setGender(cmd.gender());
        d.setDateOfBirth(cmd.dateOfBirth());
        d.setPhone(cmd.phone());
        d.setEmail(cmd.email());
        d.setRegisteredBankId(bankId);
        d.setCreatedAt(clock.instant());
        return donors.save(d);
    }

    /**
     * Records a donation and turns it into stock in ONE transaction: the donor row is locked, the
     * 90-day rule is checked, the donation is saved and inventory creates one unit per component
     * with expiry = collection time + component shelf life.
     */
    @Audited("RECORD_DONATION")
    @Transactional
    public DonationResult recordDonation(Long donorId, NewDonation cmd) {
        Long bankId = bankAccess.resolveBank(cmd.bankId());
        Donor donor = donors.findForUpdate(donorId).orElseThrow(() -> new NotFoundException("Donor", donorId));
        Instant now = clock.instant();
        Instant collectedAt = cmd.collectedAt() == null ? now : cmd.collectedAt();
        if (collectedAt.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw new BusinessRuleException("Collection time cannot be in the future");
        }
        EligibilityPolicy.whyIneligible(donor.getDateOfBirth(), donor.getLastDonationAt(), collectedAt)
                .ifPresent(reason -> {
                    throw new BusinessRuleException(reason);
                });

        Donation donation = new Donation();
        donation.setDonorId(donorId);
        donation.setBankId(bankId);
        donation.setCollectedAt(collectedAt);
        donation.setVolumeMl(cmd.volumeMl());
        donation.setRecordedBy(CurrentUser.actor());
        donation.setCreatedAt(now);
        donations.saveAndFlush(donation); // flush to get the id used in unit codes

        donor.setLastDonationAt(collectedAt);
        List<ComponentType> components = cmd.components() == null || cmd.components().isEmpty()
                ? Arrays.asList(ComponentType.separatedComponents()) : cmd.components().stream().distinct().toList();
        List<UnitView> units = unitIntake.createUnitsForDonation(donation.getId(), bankId, donor.getBloodGroup(),
                collectedAt, components);
        return new DonationResult(donation, units);
    }

    @Transactional(readOnly = true)
    public Page<Donor> search(String q, Pageable pageable) {
        return donors.search(q == null || q.isBlank() ? null : q.trim(), pageable);
    }

    @Transactional(readOnly = true)
    public Donor get(Long id) {
        return donors.findById(id).orElseThrow(() -> new NotFoundException("Donor", id));
    }

    @Transactional(readOnly = true)
    public List<Donation> history(Long donorId) {
        get(donorId);
        return donations.findByDonorIdOrderByCollectedAtDesc(donorId);
    }
}
