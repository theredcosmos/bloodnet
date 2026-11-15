package com.bloodnet.transfer;

/** Generic event for every transition; consumed asynchronously by notifications. */
public record TransferStatusChanged(Long transferId, Long fromBankId, Long toBankId, TransferStatus status) {
}
