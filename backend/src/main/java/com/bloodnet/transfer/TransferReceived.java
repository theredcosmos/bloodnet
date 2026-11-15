package com.bloodnet.transfer;

/** Published when the receiving bank confirms arrival; inventory moves the units to that bank. */
public record TransferReceived(Long transferId, Long fromBankId, Long toBankId) {
}
