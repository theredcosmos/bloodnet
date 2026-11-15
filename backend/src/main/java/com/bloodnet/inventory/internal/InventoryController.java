package com.bloodnet.inventory.internal;

import com.bloodnet.inventory.BankDirectory;
import com.bloodnet.inventory.BankInfo;
import com.bloodnet.inventory.NearbyBank;
import com.bloodnet.inventory.UnitView;
import com.bloodnet.shared.AuthUser;
import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.CurrentUser;
import com.bloodnet.shared.PageResponse;
import com.bloodnet.shared.UnitStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class InventoryController {

    private static final Set<String> SORTABLE = Set.of("expiresAt", "collectedAt", "bloodGroup", "componentType", "status", "unitCode");

    private final InventoryService service;
    private final BankDirectory banks;
    private final BankAccess bankAccess;
    private final RebalanceService rebalance;

    /** Inventory list with filters, pagination and whitelisted sorting (e.g. sort=expiresAt,asc). */
    @GetMapping("/inventory/units")
    @PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
    public PageResponse<UnitView> units(@RequestParam(required = false) Long bankId,
                                        @RequestParam(required = false) BloodGroup bloodGroup,
                                        @RequestParam(required = false) ComponentType component,
                                        @RequestParam(required = false) UnitStatus status,
                                        @RequestParam(required = false) @Min(0) @Max(400) Integer expiringWithinDays,
                                        @RequestParam(required = false) String code,
                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                        @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
                                        @RequestParam(defaultValue = "expiresAt,asc") String sort) {
        Long scopedBank = CurrentUser.get().isAdmin() ? bankId : bankAccess.resolveBank(bankId);
        return PageResponse.of(service.search(scopedBank, bloodGroup, component, status, expiringWithinDays, code,
                PageRequest.of(page, size, parseSort(sort))), InventoryViews::toView);
    }

    @PostMapping("/inventory/units/{id}/discard")
    @PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
    public UnitView discard(@PathVariable Long id) {
        bankAccess.check(service.get(id).getBankId());
        return InventoryViews.toView(service.discard(id));
    }

    @GetMapping("/banks")
    public List<BankInfo> banks() {
        return banks.all();
    }

    @GetMapping("/banks/{id}")
    public BankInfo bank(@PathVariable Long id) {
        return banks.get(id);
    }

    /**
     * Banks within radiusKm that hold stock. Staff default to their own bank's location;
     * hospitals pass their own lat/lng. includeCompatible=true counts compatible donor groups too.
     */
    @GetMapping("/banks/nearby")
    public List<NearbyBank> nearby(@RequestParam(required = false) Double lat,
                                   @RequestParam(required = false) Double lng,
                                   @RequestParam(defaultValue = "25") @Min(1) @Max(500) double radiusKm,
                                   @RequestParam(required = false) BloodGroup bloodGroup,
                                   @RequestParam(required = false) ComponentType component,
                                   @RequestParam(defaultValue = "true") boolean includeCompatible) {
        AuthUser user = CurrentUser.get();
        Long exclude = user.bankId();
        if (lat == null || lng == null) {
            if (user.bankId() == null) {
                throw new BusinessRuleException("lat and lng are required");
            }
            BankInfo own = banks.get(user.bankId());
            lat = own.latitude();
            lng = own.longitude();
        }
        return service.nearby(lat, lng, radiusKm, bloodGroup, component, includeCompatible, exclude);
    }

    @GetMapping("/inventory/rebalance-suggestions")
    @PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
    public List<RebalanceService.Suggestion> suggestions(@RequestParam(required = false) Long bankId) {
        return rebalance.openFor(CurrentUser.get().isAdmin() ? bankId : bankAccess.resolveBank(bankId));
    }

    @PostMapping("/inventory/rebalance-suggestions/{id}/dismiss")
    @PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
    public void dismiss(@PathVariable Long id) {
        rebalance.dismiss(id);
    }

    private static Sort parseSort(String sort) {
        String[] parts = sort.split(",");
        String field = parts[0].trim();
        if (!SORTABLE.contains(field)) {
            throw new BusinessRuleException("Cannot sort by '" + field + "'");
        }
        boolean desc = parts.length > 1 && parts[1].trim().equalsIgnoreCase("desc");
        return desc ? Sort.by(field).descending().and(Sort.by("id")) : Sort.by(field).ascending().and(Sort.by("id"));
    }
}
