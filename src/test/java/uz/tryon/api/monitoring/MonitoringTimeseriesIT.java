package uz.tryon.api.monitoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import uz.tryon.api.auth.AuthService;
import uz.tryon.api.auth.Client;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test: GET /api/monitoring/timeseries against real Postgres.
 *
 * Exercises the date_trunc() native query that H2 cannot parse. Testcontainers spins up
 * a real Postgres instance; Flyway runs all migrations before tests start.
 *
 * Assertions cover: bucketing per range, apiKeyId filter, tenant isolation, invalid range → 400.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MonitoringTimeseriesIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("simatest")
            .withUsername("sima")
            .withPassword("sima");

    @DynamicPropertySource
    static void pgProps(DynamicPropertyRegistry reg) {
        reg.add("spring.datasource.url", PG::getJdbcUrl);
        reg.add("spring.datasource.username", PG::getUsername);
        reg.add("spring.datasource.password", PG::getPassword);
        reg.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        reg.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        reg.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        reg.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthService authService;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private Client clientA;
    private Client clientB;
    private UUID keyA;
    private UUID keyB;
    private String tokenA;
    private String tokenB;

    @BeforeEach
    void setUp() {
        // Delete in FK-safe order: credit_transactions first (refs api_keys + clients),
        // then wallets, api_keys, clients. Clients ON DELETE CASCADE handles wallets/api_keys,
        // but credit_transactions.api_key_id has no cascade, so clear it first.
        jdbc.execute("DELETE FROM credit_transactions");
        jdbc.execute("DELETE FROM clients");   // cascades wallets + api_keys

        int seq = SEQ.getAndAdd(2);
        clientA = authService.register("ClientA", "+9989111" + seq + "001", null, "pass");
        clientB = authService.register("ClientB", "+9989111" + (seq + 1) + "001", null, "pass");

        tokenA = authService.issueSessionToken(clientA);
        tokenB = authService.issueSessionToken(clientB);

        keyA = UUID.randomUUID();
        keyB = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys(id,client_id,name,key_prefix,key_hash,created_at) VALUES(?,?,?,?,?,NOW())",
                keyA, clientA.getId(), "KeyA", "sk_a_test", "hash_a_" + seq);
        jdbc.update("INSERT INTO api_keys(id,client_id,name,key_prefix,key_hash,created_at) VALUES(?,?,?,?,?,NOW())",
                keyB, clientB.getId(), "KeyB", "sk_b_test", "hash_b_" + seq);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void debit(UUID clientId, UUID apiKeyId, Instant at) {
        jdbc.update("INSERT INTO credit_transactions(id,client_id,amount_msim,type,balance_after_msim,api_key_id,created_at) "
                        + "VALUES(?,?,-1000,'TRYON_DEBIT',99000,?,?)",
                UUID.randomUUID(), clientId, apiKeyId, at);
    }

    private long totalCount(JsonNode buckets) {
        long sum = 0;
        for (JsonNode b : buckets) sum += b.get("count").asLong();
        return sum;
    }

    // ── 1. Hourly bucketing — 24-hour window ─────────────────────────────────

    @Test
    void timeseries_hourly_bucketsCorrectly() throws Exception {
        ZonedDateTime hourStart = Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);

        // 3 debits in current hour, 1 debit 5 hours ago
        debit(clientA.getId(), keyA, hourStart.minusMinutes(5).toInstant());
        debit(clientA.getId(), keyA, hourStart.minusMinutes(3).toInstant());
        debit(clientA.getId(), keyA, hourStart.minusMinutes(1).toInstant());
        debit(clientA.getId(), keyA, hourStart.minusHours(5).toInstant());

        String body = mvc.perform(get("/api/monitoring/timeseries?range=hourly")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("hourly"))
                .andExpect(jsonPath("$.buckets").isArray())
                .andReturn().getResponse().getContentAsString();

        JsonNode buckets = mapper.readTree(body).get("buckets");
        assertEquals(2, buckets.size(), "2 non-empty buckets: current hour + 5h-ago bucket");

        // The most-recent bucket (current hour) should have count=3 and spentSim=3.0
        JsonNode latestBucket = buckets.get(buckets.size() - 1);
        assertEquals(3, latestBucket.get("count").asLong(), "Current-hour bucket count");
        assertEquals(3.0, latestBucket.get("spentSim").asDouble(), 0.001, "Current-hour bucket spentSim");
    }

    // ── 2. Daily bucketing — 30-day window ────────────────────────────────────

    @Test
    void timeseries_daily_bucketsCorrectly() throws Exception {
        ZonedDateTime dayStart = Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

        debit(clientA.getId(), keyA, dayStart.plusHours(1).toInstant());   // today
        debit(clientA.getId(), keyA, dayStart.plusHours(2).toInstant());   // today
        debit(clientA.getId(), keyA, dayStart.minusDays(15).toInstant());  // 15d ago (in window)
        debit(clientA.getId(), keyA, dayStart.minusDays(31).toInstant());  // 31d ago (outside 30d window)

        String body = mvc.perform(get("/api/monitoring/timeseries?range=daily")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.range").value("daily"))
                .andReturn().getResponse().getContentAsString();

        JsonNode buckets = mapper.readTree(body).get("buckets");
        assertEquals(2, buckets.size(), "2 buckets in 30-day window; 31d-ago row excluded");
        assertEquals(3, totalCount(buckets), "3 debits total across both buckets");
    }

    // ── 3. Weekly bucketing — 12-week window ──────────────────────────────────

    @Test
    void timeseries_weekly_bucketsCorrectly() throws Exception {
        ZonedDateTime dayStart = Instant.now().atZone(ZoneOffset.UTC).truncatedTo(ChronoUnit.DAYS);

        debit(clientA.getId(), keyA, dayStart.minusDays(1).toInstant());    // this week
        debit(clientA.getId(), keyA, dayStart.minusWeeks(5).toInstant());   // 5w ago (in 12w window)
        debit(clientA.getId(), keyA, dayStart.minusWeeks(13).toInstant());  // 13w ago (outside window)

        String body = mvc.perform(get("/api/monitoring/timeseries?range=weekly")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode buckets = mapper.readTree(body).get("buckets");
        assertEquals(2, buckets.size(), "2 buckets in 12-week window; 13w-ago row excluded");
    }

    // ── 4. Monthly bucketing — 12-month window ────────────────────────────────

    @Test
    void timeseries_monthly_bucketsCorrectly() throws Exception {
        ZonedDateTime monthStart = Instant.now().atZone(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);

        debit(clientA.getId(), keyA, monthStart.plusDays(1).toInstant());        // this month
        debit(clientA.getId(), keyA, monthStart.minusMonths(6).toInstant());     // 6mo ago
        debit(clientA.getId(), keyA, monthStart.minusMonths(6).plusDays(5).toInstant()); // same 6mo bucket
        debit(clientA.getId(), keyA, monthStart.minusMonths(13).toInstant());    // 13mo ago (outside window)

        String body = mvc.perform(get("/api/monitoring/timeseries?range=monthly")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode buckets = mapper.readTree(body).get("buckets");
        assertEquals(2, buckets.size(), "2 buckets in 12-month window; 13mo-ago row excluded");
        assertTrue(buckets.size() <= 12, "Monthly range must yield at most 12 buckets");

        // 6-month-ago bucket should have count=2 (two debits in same month)
        JsonNode olderBucket = buckets.get(0);
        assertEquals(2, olderBucket.get("count").asLong(), "6mo-ago bucket has 2 debits");
    }

    // ── 5. apiKeyId filter narrows results to one key ─────────────────────────

    @Test
    void timeseries_apiKeyFilter_narrowsToOneKey() throws Exception {
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        Instant recent = now.minusHours(1).toInstant();

        UUID keyA2 = UUID.randomUUID();
        int seq = SEQ.getAndIncrement();
        jdbc.update("INSERT INTO api_keys(id,client_id,name,key_prefix,key_hash,created_at) VALUES(?,?,?,?,?,NOW())",
                keyA2, clientA.getId(), "KeyA2", "sk_a2_tst", "hash_a2_" + seq);

        debit(clientA.getId(), keyA, recent);
        debit(clientA.getId(), keyA, recent);
        debit(clientA.getId(), keyA, recent);  // 3 via keyA
        debit(clientA.getId(), keyA2, recent);
        debit(clientA.getId(), keyA2, recent); // 2 via keyA2

        // Without filter: 5 total
        String bodyAll = mvc.perform(get("/api/monitoring/timeseries?range=hourly")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(5, totalCount(mapper.readTree(bodyAll).get("buckets")), "All 5 debits without filter");

        // Filtered by keyA: 3
        String bodyKeyA = mvc.perform(get("/api/monitoring/timeseries?range=hourly&apiKeyId=" + keyA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(3, totalCount(mapper.readTree(bodyKeyA).get("buckets")), "3 debits via keyA filter");

        // Filtered by keyA2: 2
        String bodyKeyA2 = mvc.perform(get("/api/monitoring/timeseries?range=hourly&apiKeyId=" + keyA2)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(2, totalCount(mapper.readTree(bodyKeyA2).get("buckets")), "2 debits via keyA2 filter");
    }

    // ── 6. Tenant isolation: clientA's apiKeyId passed by clientB → 0 results ─

    @Test
    void timeseries_tenantIsolation_crossClientKeyReturnsEmpty() throws Exception {
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        Instant recent = now.minusHours(1).toInstant();

        // Seed 2 debits for clientB attributed to keyB
        debit(clientB.getId(), keyB, recent);
        debit(clientB.getId(), keyB, recent);

        // ClientA queries with clientB's apiKeyId — tenant filter (client_id = clientA) prevents any match
        String body = mvc.perform(get("/api/monitoring/timeseries?range=hourly&apiKeyId=" + keyB)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode buckets = mapper.readTree(body).get("buckets");
        assertEquals(0, buckets.size(), "Tenant isolation: clientA sees 0 buckets for clientB's apiKeyId");
    }

    // ── 7. Tenant isolation: clientB sees only its own data ───────────────────

    @Test
    void timeseries_tenantIsolation_clientsDoNotSeeEachOthersData() throws Exception {
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        Instant recent = now.minusHours(1).toInstant();

        debit(clientA.getId(), keyA, recent);
        debit(clientA.getId(), keyA, recent);

        // ClientB's timeseries must be empty (its own data = 0 debits)
        String body = mvc.perform(get("/api/monitoring/timeseries?range=hourly")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(0, mapper.readTree(body).get("buckets").size(),
                "ClientB must see 0 buckets: clientA's debits are tenant-isolated");
    }

    // ── 8. Invalid range → HTTP 400 ───────────────────────────────────────────

    @Test
    void timeseries_unknownRange_400() throws Exception {
        mvc.perform(get("/api/monitoring/timeseries?range=yearly")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void timeseries_missingRange_400() throws Exception {
        // Spring MVC rejects a missing required @RequestParam with 400
        mvc.perform(get("/api/monitoring/timeseries")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest());
    }

    // ── 9. No auth → HTTP 401 ─────────────────────────────────────────────────

    @Test
    void timeseries_noAuth_401() throws Exception {
        mvc.perform(get("/api/monitoring/timeseries?range=hourly"))
                .andExpect(status().isUnauthorized());
    }
}
