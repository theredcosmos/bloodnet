package com.bloodnet.transfer.internal;

import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.BloodGroup;
import com.bloodnet.shared.ComponentType;
import com.bloodnet.shared.CurrentUser;
import com.bloodnet.transfer.TransferStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
public class TransferController {

    private final TransferService service;
    private final BankAccess bankAccess;

    public record CreateTransferRequest(@NotNull Long fromBankId, Long toBankId, @NotNull BloodGroup bloodGroup,
                                        @NotNull ComponentType componentType, @Min(1) @Max(50) int units,
                                        @Size(max = 255) String notes) {
    }

    public record TransferView(Long id, Long fromBankId, Long toBankId, BloodGroup bloodGroup,
                               ComponentType componentType, int units, TransferStatus status,
                               List<TransferStatus> allowedNext, String notes, String requestedBy,
                               Instant createdAt, Instant updatedAt) {
        static TransferView of(Transfer t) {
            return new TransferView(t.getId(), t.getFromBankId(), t.getToBankId(), t.getBloodGroup(),
                    t.getComponentType(), t.getUnitsCount(), t.getStatus(), List.copyOf(t.getStatus().next()),
                    t.getNotes(), t.getRequestedBy(), t.getCreatedAt(), t.getUpdatedAt());
        }
    }

    public record StepView(TransferStatus fromStatus, TransferStatus toStatus, String actor, Instant at) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransferView create(@Valid @RequestBody CreateTransferRequest req) {
        Long toBank = bankAccess.resolveBank(req.toBankId());
        return TransferView.of(service.create(new TransferService.CreateTransfer(req.fromBankId(), toBank,
                req.bloodGroup(), req.componentType(), req.units(), req.notes())));
    }

    @GetMapping
    public List<TransferView> list(@RequestParam(defaultValue = "INCOMING") String direction,
                                   @RequestParam(required = false) Long bankId) {
        Long scoped = CurrentUser.get().isAdmin() ? bankId : bankAccess.resolveBank(bankId);
        return service.list(scoped, direction).stream().map(TransferView::of).toList();
    }

    @GetMapping("/{id}")
    public TransferView get(@PathVariable Long id) {
        return TransferView.of(service.get(id));
    }

    @GetMapping("/{id}/timeline")
    public List<StepView> timeline(@PathVariable Long id) {
        return service.timeline(id).stream()
                .map(e -> new StepView(e.getFromStatus(), e.getToStatus(), e.getActor(), e.getCreatedAt())).toList();
    }

    @PostMapping("/{id}/approve")
    public TransferView approve(@PathVariable Long id) {
        return TransferView.of(service.transition(id, TransferStatus.APPROVED));
    }

    @PostMapping("/{id}/reject")
    public TransferView reject(@PathVariable Long id) {
        return TransferView.of(service.transition(id, TransferStatus.REJECTED));
    }

    @PostMapping("/{id}/dispatch")
    public TransferView dispatch(@PathVariable Long id) {
        return TransferView.of(service.transition(id, TransferStatus.DISPATCHED));
    }

    @PostMapping("/{id}/receive")
    public TransferView receive(@PathVariable Long id) {
        return TransferView.of(service.transition(id, TransferStatus.RECEIVED));
    }

    @PostMapping("/{id}/cancel")
    public TransferView cancel(@PathVariable Long id) {
        return TransferView.of(service.transition(id, TransferStatus.CANCELLED));
    }
}
