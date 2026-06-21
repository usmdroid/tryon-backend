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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test: GET /api/monitoring/history against real Postgres.
 *
 * Exercises the native paginated query (LEFT JOIN api_keys, CAST(:apiKeyId AS uuid),
 * snake_case projection). Testcontainers spins up Postgres; Flyway runs migrations.
 *
 * Covers: created_at DESC ordering, key name/prefix join, result (meta → "success"),
 * pagination (limit/offset + total), apiKeyId filter, tenant isolation, 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MonitoringHistoryIT {

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
        jdbc.execute("DELETE FROM credit_transactions");
        jdbc.execute("DELETE FROM clients");

        int seq = SEQ.getAndAdd(2);
        clientA = authService.register("ClientA", "+9989222" + seq + "001", null, "pass");
        clientB = authService.register("ClientB", "+9989222" + (seq + 1) + "001", null, "pass");

        tokenA = authService.issueSessionToken(clientA);
        tokenB = authService.issueSessionToken(clientB);

        keyA = UUID.randomUUID();
        keyB = UUID.randomUUID();
        jdbc.update("INSERT INTO api_keys(id,client_id,name,key_prefix,key_hash,created_at) VALUES(?,?,?,?,?,NOW())",
                keyA, clientA.getId(), "KeyA", "sk_a_test", "hash_a_" + seq);
        jdbc.update("INSERT INTO api_keys(id,client_id,name,key_prefix,key_hash,created_at) VALUES(?,?,?,?,?,NOW())",
                keyB, clientB.getId(), "KeyB", "sk_b_test", "hash_b_" + seq);
    }

    private void debit(UUID clientId, UUID apiKeyId, Instant at, String meta) {
        jdbc.update("INSERT INTO credit_transactions(id,client_id,amount_msim,type,balance_after_msim,meta,api_key_id,created_at) "
                        + "VALUES(?,?,-1000,'TRYON_DEBIT',99000,?,?,?)",
                UUID.randomUUID(), clientId, meta, apiKeyId, at);
    }

    // ── 1. Ordering DESC + key join + result default ──────────────────────────

    @Test
    void history_returnsRowsDescWithKeyInfoAndResult() throws Exception {
        Instant now = Instant.now();
        debit(clientA.getId(), keyA, now.minusSeconds(30), null);     // older, no meta → "success"
        debit(clientA.getId(), keyA, now.minusSeconds(10), "failed"); // newer, meta passthrough

        String body = mvc.perform(get("/api/monitoring/history")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0))
                .andExpect(jsonPath("$.items").isArray())
                .andReturn().getResponse().getContentAsString();

        JsonNode items = mapper.readTree(body).get("items");
        assertEquals(2, items.size(), "Two history rows for clientA");

        // Newest first
        JsonNode first = items.get(0);
        assertEquals("failed", first.get("result").asText(), "meta passthrough");
        assertEquals("KeyA", first.get("keyName").asText(), "joined key name");
        assertEquals("sk_a_test", first.get("keyPrefix").asText(), "joined key prefix");
        assertEquals(1.0, first.get("spentSim").asDouble(), 0.001, "1000 msim → 1.0 sim");

        JsonNode second = items.get(1);
        assertEquals("success", second.get("result").asText(), "null meta → default success");
    }

    // ── 2. Pagination: limit + offset, total stays full count ─────────────────

    @Test
    void history_pagination_limitAndOffset() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) {
            debit(clientA.getId(), keyA, now.minusSeconds(i), null);
        }

        String page1 = mvc.perform(get("/api/monitoring/history?limit=2&offset=0")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.limit").value(2))
                .andReturn().getResponse().getContentAsString();
        assertEquals(2, mapper.readTree(page1).get("items").size(), "limit=2 → 2 items");

        String page3 = mvc.perform(get("/api/monitoring/history?limit=2&offset=4")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andReturn().getResponse().getContentAsString();
        assertEquals(1, mapper.readTree(page3).get("items").size(), "offset=4 → last 1 item");
    }

    // ── 3. apiKeyId filter narrows to one key ─────────────────────────────────

    @Test
    void history_apiKeyFilter_narrowsToOneKey() throws Exception {
        Instant now = Instant.now();
        UUID keyA2 = UUID.randomUUID();
        int seq = SEQ.getAndIncrement();
        jdbc.update("INSERT INTO api_keys(id,client_id,name,key_prefix,key_hash,created_at) VALUES(?,?,?,?,?,NOW())",
                keyA2, clientA.getId(), "KeyA2", "sk_a2_tst", "hash_a2_" + seq);

        debit(clientA.getId(), keyA, now.minusSeconds(3), null);
        debit(clientA.getId(), keyA, now.minusSeconds(2), null);
        debit(clientA.getId(), keyA2, now.minusSeconds(1), null);

        String filtered = mvc.perform(get("/api/monitoring/history?apiKeyId=" + keyA)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andReturn().getResponse().getContentAsString();
        assertEquals(2, mapper.readTree(filtered).get("items").size(), "Only keyA rows");
    }

    // ── 4. Tenant isolation: clientB sees only its own data ───────────────────

    @Test
    void history_tenantIsolation() throws Exception {
        Instant now = Instant.now();
        debit(clientA.getId(), keyA, now.minusSeconds(1), null);
        debit(clientA.getId(), keyA, now.minusSeconds(2), null);

        String body = mvc.perform(get("/api/monitoring/history")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andReturn().getResponse().getContentAsString();
        assertEquals(0, mapper.readTree(body).get("items").size(),
                "ClientB sees none of clientA's history");
    }

    // ── 5. No auth → 401 ──────────────────────────────────────────────────────

    @Test
    void history_noAuth_401() throws Exception {
        mvc.perform(get("/api/monitoring/history"))
                .andExpect(status().isUnauthorized());
    }
}
