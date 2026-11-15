package com.bloodnet.reporting.internal;

import com.bloodnet.inventory.InventoryStats;
import com.bloodnet.inventory.UnitView;
import com.bloodnet.inventory.WastageRow;
import com.bloodnet.request.RequestQueries;
import com.bloodnet.request.RequestStatus;
import com.bloodnet.shared.BankAccess;
import com.bloodnet.shared.BusinessRuleException;
import com.bloodnet.shared.CurrentUser;
import com.bloodnet.transfer.TransferQueries;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
public class ReportingController {

    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryStats stats;
    private final RequestQueries requestQueries;
    private final TransferQueries transferQueries;
    private final StockSummaryService summary;
    private final BankAccess bankAccess;
    private final Clock clock;

    public record StockCell(String bloodGroup, String componentType, long available, long reserved, long expiring48h) {
    }

    public record Dashboard(Long bankId, Instant refreshedAt, long totalAvailable, long totalReserved,
                            long totalExpiring48h, List<StockCell> stock, Map<RequestStatus, Long> requests,
                            Long openIncomingTransfers, Long openOutgoingTransfers) {
    }

    /** Reads the pre-aggregated stock_summary; admins without bankId get the network-wide total. */
    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public Dashboard dashboard(@RequestParam(required = false) Long bankId) {
        Long scoped = CurrentUser.get().isAdmin() ? bankId : bankAccess.resolveBank(bankId);
        MapSqlParameterSource p = new MapSqlParameterSource("bankId", scoped);
        List<StockCell> cells = jdbc.query("""
                SELECT blood_group, component_type, SUM(available), SUM(reserved), SUM(expiring_48h)
                FROM stock_summary
                WHERE (:bankId IS NULL OR bank_id = :bankId)
                GROUP BY blood_group, component_type
                ORDER BY blood_group, component_type
                """, p, (rs, i) -> new StockCell(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
        Timestamp refreshed = jdbc.queryForObject("SELECT MAX(refreshed_at) FROM stock_summary", p, Timestamp.class);
        return new Dashboard(scoped, refreshed == null ? null : refreshed.toInstant(),
                cells.stream().mapToLong(StockCell::available).sum(),
                cells.stream().mapToLong(StockCell::reserved).sum(),
                cells.stream().mapToLong(StockCell::expiring48h).sum(),
                cells, requestQueries.countsByStatus(scoped),
                scoped == null ? null : transferQueries.openIncoming(scoped),
                scoped == null ? null : transferQueries.openOutgoing(scoped));
    }

    /** Lets an admin force a refresh (e.g. right after a demo data load) instead of waiting for the job. */
    @PostMapping("/dashboard/refresh")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Integer> refresh() {
        return Map.of("cells", summary.refresh());
    }

    @GetMapping("/reports/expiring")
    public List<UnitView> expiring(@RequestParam(required = false) Long bankId,
                                   @RequestParam(defaultValue = "3") @Min(1) @Max(60) int days) {
        Long scoped = CurrentUser.get().isAdmin() ? bankId : bankAccess.resolveBank(bankId);
        Instant now = clock.instant();
        return stats.expiring(scoped, now, now.plus(Duration.ofDays(days)));
    }

    @GetMapping("/reports/wastage")
    public List<WastageRow> wastage(@RequestParam(required = false) Long bankId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (to.isBefore(from)) throw new BusinessRuleException("'to' must not be before 'from'");
        Long scoped = CurrentUser.get().isAdmin() ? bankId : bankAccess.resolveBank(bankId);
        return stats.wastage(scoped, from.atStartOfDay().toInstant(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    }
}
