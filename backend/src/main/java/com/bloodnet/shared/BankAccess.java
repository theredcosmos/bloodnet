package com.bloodnet.shared;

import org.springframework.stereotype.Component;

/**
 * Row-level authorization: bank staff may only touch their own bank.
 * Used declaratively from SpEL ({@code @PreAuthorize("@bankAccess.canAccess(#bankId)")})
 * and programmatically when the bank is only known after loading an entity.
 */
@Component("bankAccess")
public class BankAccess {

    public boolean canAccess(Long bankId) {
        return CurrentUser.find()
                .map(u -> u.isAdmin() || (u.role() == Role.BANK_STAFF && bankId != null && bankId.equals(u.bankId())))
                .orElse(false);
    }

    public void check(Long bankId) {
        if (!canAccess(bankId)) {
            throw new ForbiddenOperationException("You can only act on your own blood bank");
        }
    }

    /** Staff default to their own bank; admins must say which bank. */
    public Long resolveBank(Long requested) {
        AuthUser u = CurrentUser.get();
        Long bankId = requested != null ? requested : u.bankId();
        if (bankId == null) {
            throw new BusinessRuleException("bankId is required");
        }
        check(bankId);
        return bankId;
    }
}
