package com.bloodnet.inventory;

/** Published when the rebalance job finds near-expiry units that a nearby bank is short of. */
public record RebalanceSuggested(Long fromBankId, Long toBankId, int units) {
}
