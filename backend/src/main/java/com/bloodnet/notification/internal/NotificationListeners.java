package com.bloodnet.notification.internal;

import com.bloodnet.inventory.RebalanceSuggested;
import com.bloodnet.inventory.UnitsExpired;
import com.bloodnet.request.RequestEvents.RequestCancelled;
import com.bloodnet.request.RequestEvents.RequestCreated;
import com.bloodnet.request.RequestEvents.RequestIssued;
import com.bloodnet.request.RequestEvents.RequestReserved;
import com.bloodnet.request.RequestEvents.ReservationExpired;
import com.bloodnet.transfer.TransferStatusChanged;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Writes inbox notifications for banks and hospitals.
 *
 * @ApplicationModuleListener runs async, after the publishing transaction commits, in a new
 * transaction. Modulith also saves each event to EVENT_PUBLICATION in the publisher's transaction,
 * so if the app dies before a listener finishes the event is resubmitted on restart.
 * A bug in here can't undo an allocation or a transfer.
 * If notifications move to their own service later, this is where RabbitMQ would come in.
 */
@Component
@RequiredArgsConstructor
class NotificationListeners {

    private final NotificationRepository repo;
    private final Clock clock;

    @ApplicationModuleListener
    void on(RequestCreated e) {
        bank(e.bankId(), "REQUEST_CREATED", "New %s request #%d: %d x %s %s".formatted(
                e.urgency(), e.requestId(), e.units(), e.bloodGroup().label(), e.componentType()));
    }

    @ApplicationModuleListener
    void on(RequestReserved e) {
        hospital(e.hospitalId(), "REQUEST_RESERVED", "Request #%d: %d of %d units reserved".formatted(
                e.requestId(), e.allocated(), e.requested()));
    }

    @ApplicationModuleListener
    void on(RequestIssued e) {
        hospital(e.hospitalId(), "REQUEST_ISSUED", "Request #%d issued: %d units ready".formatted(e.requestId(), e.units()));
    }

    @ApplicationModuleListener
    void on(RequestCancelled e) {
        bank(e.bankId(), "REQUEST_CANCELLED", "Request #%d was cancelled".formatted(e.requestId()));
    }

    @ApplicationModuleListener
    void on(ReservationExpired e) {
        String msg = "Reservation for request #%d expired; %d units released".formatted(e.requestId(), e.releasedUnits());
        bank(e.bankId(), "RESERVATION_EXPIRED", msg);
        hospital(e.hospitalId(), "RESERVATION_EXPIRED", msg);
    }

    @ApplicationModuleListener
    void on(TransferStatusChanged e) {
        String msg = "Transfer #%d is now %s".formatted(e.transferId(), e.status());
        bank(e.fromBankId(), "TRANSFER_" + e.status(), msg);
        bank(e.toBankId(), "TRANSFER_" + e.status(), msg);
    }

    @ApplicationModuleListener
    void on(UnitsExpired e) {
        bank(e.bankId(), "UNITS_EXPIRED", "%d units expired and were removed from stock".formatted(e.units()));
    }

    @ApplicationModuleListener
    void on(RebalanceSuggested e) {
        bank(e.fromBankId(), "REBALANCE", "%d near-expiry units could go to bank #%d".formatted(e.units(), e.toBankId()));
    }

    private void bank(Long bankId, String type, String message) {
        save(bankId, null, type, message);
    }

    private void hospital(Long hospitalId, String type, String message) {
        save(null, hospitalId, type, message);
    }

    private void save(Long bankId, Long hospitalId, String type, String message) {
        Notification n = new Notification();
        n.setBankId(bankId);
        n.setHospitalId(hospitalId);
        n.setType(type);
        n.setMessage(message);
        n.setCreatedAt(clock.instant());
        repo.save(n);
    }
}
