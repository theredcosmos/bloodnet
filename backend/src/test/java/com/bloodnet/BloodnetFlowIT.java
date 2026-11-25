package com.bloodnet;

import com.bloodnet.inventory.internal.ExpiryService;
import com.bloodnet.request.internal.AllocationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through HTTP: real login, real JWT, real MySQL. Covers the main business flows
 * and the security rules around them.
 */
@AutoConfigureMockMvc
class BloodnetFlowIT extends IntegrationTestBase {

    private static final String PASSWORD = "Test-Only-Pass1";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExpiryService expiry;
    @Autowired AllocationService allocation;

    private long bank1;
    private long bank2;

    @BeforeEach
    void banks() {
        bank1 = jdbc.queryForObject("SELECT id FROM blood_bank WHERE code = 'BB-PUN-01'", Long.class);
        bank2 = jdbc.queryForObject("SELECT id FROM blood_bank WHERE code = 'BB-PUN-02'", Long.class);
    }

    @Test
    void compatibilityMatrixIsSeeded() {
        // red cells + whole blood: 27 pairs each; plasma + platelets: 9 ABO pairs x 4 Rh combos each
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blood_compatibility", Integer.class)).isEqualTo(27 * 2 + 36 * 2);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM blood_compatibility WHERE recipient_group = 'O_NEG' AND component_type = 'RED_CELLS'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM blood_compatibility WHERE recipient_group = 'AB_POS' AND component_type = 'RED_CELLS'
                """, Integer.class)).isEqualTo(8);
    }

    @Test
    void securityRules() throws Exception {
        mvc.perform(get("/api/inventory/units")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/inventory/units").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"staff1@bloodnet.local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        String staff1 = login("staff1@bloodnet.local");
        String hospital = login("hospital@bloodnet.local");
        // staff cannot read another bank's inventory
        mvc.perform(get("/api/inventory/units").param("bankId", String.valueOf(bank2)).header("Authorization", staff1))
                .andExpect(status().isForbidden());
        // hospital role cannot see inventory or register donors
        mvc.perform(get("/api/inventory/units").header("Authorization", hospital)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").header("Authorization", staff1)).andExpect(status().isForbidden());
        mvc.perform(get("/api/auth/me").header("Authorization", staff1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("BANK_STAFF"));
    }

    @Test
    void publicAvailabilityNeedsNoLoginAndShowsOnlyCounts() throws Exception {
        String staff1 = login("staff1@bloodnet.local");
        donate(staff1, registerDonor(staff1, "O_NEG", "9000000006"));

        // an O+ patient can take O- red cells, so bank 1 must show stock for an O+ search
        String body = mvc.perform(get("/api/public/availability")
                        .param("lat", "18.5204").param("lng", "73.8567").param("radiusKm", "25")
                        .param("bloodGroup", "O_POS").param("component", "RED_CELLS"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=30")))
                .andExpect(jsonPath("$[0].availableUnits").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$[0].phone").exists())
                .andExpect(jsonPath("$[0].stockUpdatedAt").exists())
                .andReturn().getResponse().getContentAsString();
        // nothing about donors, units or requests may leak to anonymous users
        assertThat(body).doesNotContain("unitCode", "donor", "requestId", "9000000006");

        mvc.perform(get("/api/public/banks/{id}", bank1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bank.name").value("Central Blood Bank"))
                .andExpect(jsonPath("$.stock[?(@.bloodGroup == 'O_NEG' && @.componentType == 'RED_CELLS')].available")
                        .value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.greaterThanOrEqualTo(1))));
        // other ITs share this database and add banks in their own city, so Pune need not be first
        mvc.perform(get("/api/public/areas")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].city").value(org.hamcrest.Matchers.hasItem("Pune")));

        // bad input is a clean 400, and the rest of the API still needs a token
        mvc.perform(get("/api/public/availability").param("lat", "999").param("lng", "73.8"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/inventory/units")).andExpect(status().isUnauthorized());
    }

    @Test
    void bootstrapAdminFromConfigCanLogIn() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"boot-admin@test.local\",\"password\":\"Boot-Admin-Pass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void adminSignupNeedsTheSetupCode() throws Exception {
        mvc.perform(get("/api/auth/signup-status"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.adminSignupEnabled").value(true));

        String body = """
                {"email":"new-admin@test.local","fullName":"New Admin","password":"Strong-Pass-99","setupCode":"%s"}""";
        mvc.perform(post("/api/auth/signup-admin").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("wrong-code-000")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/signup-admin").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("test-setup-code-1234").replace("Strong-Pass-99", "short")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.password").exists());

        String token = "Bearer " + body(mvc.perform(post("/api/auth/signup-admin").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("test-setup-code-1234")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("ADMIN"))).get("token").asText();

        // the new admin can add users straight away
        mvc.perform(post("/api/users").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", "staff-new@test.local", "fullName", "New Staff",
                                "password", "Staff-Pass-77", "role", "BANK_STAFF", "bankId", bank1))))
                .andExpect(status().isCreated());

        // same email twice is a conflict, and the setup code never lands in the audit log
        mvc.perform(post("/api/auth/signup-admin").contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("test-setup-code-1234")))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE target LIKE '%test-setup-code%' OR detail LIKE '%test-setup-code%'",
                Integer.class)).isZero();
    }

    @Test
    void donationCreatesUnitsAndEnforces90DayRule() throws Exception {
        String staff1 = login("staff1@bloodnet.local");
        long donorId = registerDonor(staff1, "A_POS", "9000000001");

        Instant collected = Instant.now().minus(Duration.ofHours(1));
        JsonNode donation = body(mvc.perform(post("/api/donors/{id}/donations", donorId).header("Authorization", staff1)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("collectedAt", collected.toString(), "volumeMl", 450))))
                .andExpect(status().isCreated()));
        assertThat(donation.get("units")).hasSize(3);
        for (JsonNode unit : donation.get("units")) {
            Instant expires = Instant.parse(unit.get("expiresAt").asText());
            long days = Duration.between(collected, expires).toDays();
            switch (unit.get("componentType").asText()) {
                case "RED_CELLS" -> assertThat(days).isEqualTo(42);
                case "PLATELETS" -> assertThat(days).isEqualTo(5);
                case "PLASMA" -> assertThat(days).isEqualTo(365);
                default -> throw new AssertionError("unexpected component");
            }
            assertThat(unit.get("bloodGroup").asText()).isEqualTo("A_POS");
        }

        // second donation the same day: 422
        mvc.perform(post("/api/donors/{id}/donations", donorId).header("Authorization", staff1)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"volumeMl\":450}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("90 days")));

        // bean validation: bad phone + missing fields come back as one 400 with field errors
        mvc.perform(post("/api/donors").header("Authorization", staff1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"\",\"phone\":\"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.phone").exists())
                .andExpect(jsonPath("$.errors.bloodGroup").exists());
    }

    @Test
    void requestAllocateIssueFlow() throws Exception {
        String staff1 = login("staff1@bloodnet.local");
        String staff2 = login("staff2@bloodnet.local");
        String hospital = login("hospital@bloodnet.local");
        long donorId = registerDonor(staff1, "B_NEG", "9000000002");
        donate(staff1, donorId);

        long requestId = body(mvc.perform(post("/api/requests").header("Authorization", hospital)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "bankId", bank1, "bloodGroup", "B_NEG", "componentType", "RED_CELLS", "units", 1,
                        "urgency", "URGENT", "patientRef", "PT-001"))))
                .andExpect(status().isCreated())).get("id").asLong();

        // staff of another bank may not allocate it
        mvc.perform(post("/api/requests/{id}/allocate", requestId).header("Authorization", staff2))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/requests/{id}/allocate", requestId).header("Authorization", staff1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newlyReserved").value(1))
                .andExpect(jsonPath("$.status").value("RESERVED"));

        mvc.perform(post("/api/requests/{id}/issue", requestId).header("Authorization", staff1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ISSUED"));
        assertThat(jdbc.queryForObject("SELECT status FROM blood_unit WHERE request_id = ?", String.class, requestId))
                .isEqualTo("ISSUED");

        // the hospital sees it; the notification arrives asynchronously after commit
        mvc.perform(get("/api/requests/{id}", requestId).header("Authorization", hospital))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ISSUED"));
        assertThat(waitFor(() -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE type = 'REQUEST_ISSUED' AND message LIKE ?",
                Integer.class, "Request #" + requestId + " %") > 0)).isTrue();

        // every state change was audited
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ALLOCATE_REQUEST' AND outcome = 'SUCCESS'",
                Integer.class)).isPositive();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ALLOCATE_REQUEST' AND outcome = 'FAILURE'",
                Integer.class)).isPositive();
    }

    @Test
    void reservationTimeoutReleasesUnits() throws Exception {
        String staff1 = login("staff1@bloodnet.local");
        String hospital = login("hospital@bloodnet.local");
        long donorId = registerDonor(staff1, "AB_NEG", "9000000003");
        donate(staff1, donorId);
        long requestId = body(mvc.perform(post("/api/requests").header("Authorization", hospital)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "bankId", bank1, "bloodGroup", "AB_NEG", "componentType", "PLASMA", "units", 1,
                        "urgency", "ROUTINE", "patientRef", "PT-002"))))).get("id").asLong();
        mvc.perform(post("/api/requests/{id}/allocate", requestId).header("Authorization", staff1)).andExpect(status().isOk());

        // pretend the 2 hours have passed
        jdbc.update("UPDATE blood_request SET reserved_until = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(Duration.ofMinutes(1))), requestId);
        assertThat(allocation.releaseIfExpired(requestId)).isTrue();

        assertThat(jdbc.queryForObject("SELECT status FROM blood_request WHERE id = ?", String.class, requestId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blood_unit WHERE request_id = ?", Integer.class, requestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM request_allocation WHERE request_id = ? AND released_at IS NULL",
                Integer.class, requestId)).isZero();
    }

    @Test
    void transferStateMachineMovesStock() throws Exception {
        String staff1 = login("staff1@bloodnet.local");
        String staff2 = login("staff2@bloodnet.local");
        long donorId = registerDonor(staff1, "O_NEG", "9000000004");
        donate(staff1, donorId);
        long unitId = jdbc.queryForObject("""
                SELECT u.id FROM blood_unit u JOIN donation d ON d.id = u.donation_id
                WHERE d.donor_id = ? AND u.component_type = 'PLASMA'
                """, Long.class, donorId);

        // nearby search from bank 2 finds bank 1 (~9 km away) with O- plasma
        mvc.perform(get("/api/banks/nearby").header("Authorization", staff2)
                        .param("radiusKm", "25").param("bloodGroup", "O_NEG").param("component", "PLASMA")
                        .param("includeCompatible", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bankId").value(bank1))
                .andExpect(jsonPath("$[0].distanceKm").value(org.hamcrest.Matchers.closeTo(8.9, 1.0)))
                .andExpect(jsonPath("$[0].availableUnits").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        long transferId = body(mvc.perform(post("/api/transfers").header("Authorization", staff2)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "fromBankId", bank1, "bloodGroup", "O_NEG", "componentType", "PLASMA", "units", 1))))
                .andExpect(status().isCreated())).get("id").asLong();

        // the requesting bank cannot approve its own request; illegal jump is a 409
        mvc.perform(post("/api/transfers/{id}/approve", transferId).header("Authorization", staff2)).andExpect(status().isForbidden());
        mvc.perform(post("/api/transfers/{id}/receive", transferId).header("Authorization", staff2)).andExpect(status().isConflict());

        mvc.perform(post("/api/transfers/{id}/approve", transferId).header("Authorization", staff1)).andExpect(status().isOk());
        mvc.perform(post("/api/transfers/{id}/dispatch", transferId).header("Authorization", staff1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPATCHED"));
        assertThat(jdbc.queryForObject("SELECT status FROM blood_unit WHERE id = ?", String.class, unitId)).isEqualTo("IN_TRANSIT");

        mvc.perform(post("/api/transfers/{id}/receive", transferId).header("Authorization", staff2))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RECEIVED"));
        assertThat(jdbc.queryForMap("SELECT status, bank_id FROM blood_unit WHERE id = ?", unitId))
                .containsEntry("status", "AVAILABLE").containsEntry("bank_id", bank2);

        mvc.perform(get("/api/transfers/{id}/timeline", transferId).header("Authorization", staff2))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void dispatchWithoutStockRollsBackStatus() throws Exception {
        String staff1 = login("staff1@bloodnet.local");
        String staff2 = login("staff2@bloodnet.local");
        long transferId = body(mvc.perform(post("/api/transfers").header("Authorization", staff2)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                        "fromBankId", bank1, "bloodGroup", "AB_POS", "componentType", "WHOLE_BLOOD", "units", 40))))
                .andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(post("/api/transfers/{id}/approve", transferId).header("Authorization", staff1)).andExpect(status().isOk());

        mvc.perform(post("/api/transfers/{id}/dispatch", transferId).header("Authorization", staff1))
                .andExpect(status().isUnprocessableEntity());
        // the inventory listener threw inside the same transaction, so the status change rolled back too
        assertThat(jdbc.queryForObject("SELECT status FROM transfer WHERE id = ?", String.class, transferId)).isEqualTo("APPROVED");
    }

    @Test
    void expiryJobMarksOldUnits() {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO blood_unit (unit_code, bank_id, blood_group, component_type, status, collected_at, expires_at,
                                        created_at, updated_at)
                VALUES ('EXPIRY-TEST', ?, 'A_NEG', 'PLATELETS', 'AVAILABLE', ?, ?, ?, ?)
                """, bank2, Timestamp.from(now.minus(Duration.ofDays(6))), Timestamp.from(now.minus(Duration.ofDays(1))),
                Timestamp.from(now), Timestamp.from(now));
        assertThat(expiry.expireUnits()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM blood_unit WHERE unit_code = 'EXPIRY-TEST'", String.class))
                .isEqualTo("EXPIRED");
    }

    @Test
    void dashboardReadsSummary() throws Exception {
        String admin = login("admin@bloodnet.local");
        String staff1 = login("staff1@bloodnet.local");
        donate(staff1, registerDonor(staff1, "O_POS", "9000000005"));
        mvc.perform(post("/api/dashboard/refresh").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").header("Authorization", staff1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankId").value(bank1))
                .andExpect(jsonPath("$.totalAvailable").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ------------------------------------------------------------------ helpers

    private String login(String email) throws Exception {
        JsonNode r = body(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isOk()));
        return "Bearer " + r.get("token").asText();
    }

    private long registerDonor(String auth, String group, String phone) throws Exception {
        return body(mvc.perform(post("/api/donors").header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("fullName", "Donor " + phone, "bloodGroup", group,
                        "gender", "FEMALE", "dateOfBirth", "1992-04-12", "phone", phone))))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    private void donate(String auth, long donorId) throws Exception {
        mvc.perform(post("/api/donors/{id}/donations", donorId).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"volumeMl\":450}")).andExpect(status().isCreated());
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static boolean waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return false;
    }
}
