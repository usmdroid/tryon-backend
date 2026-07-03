package uz.tryon.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.auth.Phones;
import uz.tryon.api.telemetry.TryOnEvent;
import uz.tryon.api.telemetry.TryOnEventRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the GPU cost aggregate query and GET /api/admin/monitoring/gpu-cost.
 *
 * Covers:
 *   - Aggregate exclusion logic: only success + non-emulator + non-null gpu_ms rows counted
 *   - Math: SUM(gpu_ms)/1000 * 0.000306, avg = total/count
 *   - Zero-rows edge: requestCount=0, totalCostUsd=0.0, avgCostUsd=0.0, no NPE from null SUM
 *   - HTTP endpoint: 401 (no auth), 403 (normal client), 200 (super-admin)
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GpuCostAggregateTest {

    private static final double GPU_USD_PER_SECOND = 0.000306;
    private static final double DELTA = 1e-9;
    private static final String OTP = "123456";
    private static final AtomicInteger SEQ = new AtomicInteger(500);

    @Autowired MockMvc mvc;
    @Autowired TryOnEventRepository repo;
    @Autowired ClientRepository clients;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanEvents() {
        repo.deleteAll();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private TryOnEvent event(String result, Long gpuMs, boolean emulator) {
        return new TryOnEvent("web", "partner_site", UUID.randomUUID(), "dev1",
                "prod1", "ProductA", "upper",
                result, "fail".equals(result) ? "err" : null,
                500L, "1.2.3.4", emulator, gpuMs);
    }

    private String uniquePhone() {
        return "+99891" + SEQ.getAndIncrement() + "00001";
    }

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private String registerAndGetToken(String phone) throws Exception {
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", phone.replaceAll("\\D", "") + "@test.uz")));
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "T", "phone", phone,
                                "email", phone.replaceAll("\\D", "") + "@test.uz",
                                "password", "parol123", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    private void makeSuperAdmin(String phone) {
        Client c = clients.findByPhone(Phones.normalize(phone)).orElseThrow();
        c.setRole("SUPER_ADMIN");
        clients.save(c);
    }

    // ── 1. Aggregate exclusion + math ─────────────────────────────────────────

    @Test
    void gpuCostAggregate_countsOnlyEligibleRows() {
        Instant from = Instant.now().minusSeconds(60);
        Instant to   = Instant.now().plusSeconds(60);

        // COUNTED: success + non-emulator + gpu_ms present
        repo.save(event("success", 20000L, false));  // +20000ms
        repo.save(event("success", 15000L, false));  // +15000ms

        // EXCLUDED: fail result
        repo.save(event("fail", 18000L, false));

        // EXCLUDED: gpu_ms IS NULL
        repo.save(event("success", null, false));

        // EXCLUDED: emulator = true
        repo.save(event("success", 19000L, true));

        TryOnEventRepository.GpuCostRow row = repo.gpuCostAggregate(from, to);

        assertEquals(2, row.getRequestCount(), "requestCount must be 2 (only the two eligible rows)");
        assertNotNull(row.getTotalDurationMs(), "SUM must not be null when there are matching rows");
        assertEquals(35000L, row.getTotalDurationMs(), "SUM(gpu_ms) must be 35000ms");

        // Derived cost math
        double totalCostUsd = row.getTotalDurationMs() / 1000.0 * GPU_USD_PER_SECOND;
        double avgCostUsd   = totalCostUsd / row.getRequestCount();

        assertEquals(0.01071, totalCostUsd, 1e-7, "totalCostUsd = 35000/1000 * 0.000306 = 0.01071");
        assertEquals(0.005355, avgCostUsd,  1e-8, "avgCostUsd = 0.01071 / 2 = 0.005355");

        // Sanity: per-request cost in realistic range ($0.001 – $0.05)
        assertTrue(avgCostUsd > 0.001 && avgCostUsd < 0.05,
                "avgCostUsd " + avgCostUsd + " must be in realistic per-request range");
    }

    // ── 2. Zero-rows edge: empty window ──────────────────────────────────────

    @Test
    void gpuCostAggregate_emptyWindow_returnsZeroRequestCount() {
        // No rows inserted
        Instant from = Instant.now().minusSeconds(60);
        Instant to   = Instant.now().plusSeconds(60);

        TryOnEventRepository.GpuCostRow row = repo.gpuCostAggregate(from, to);

        assertEquals(0, row.getRequestCount(), "requestCount must be 0 for empty window");
        // SQL SUM of zero rows returns NULL — controller guards against this
        assertNull(row.getTotalDurationMs(), "totalDurationMs must be NULL for empty window (SQL SUM behaviour)");
    }

    // ── 3. HTTP endpoint: zero rows → no NPE, returns all-zero response ───────

    @Test
    void gpuCost_endpoint_zeroRows_returnsAllZero() throws Exception {
        String phone = uniquePhone();
        String token = registerAndGetToken(phone);
        makeSuperAdmin(phone);

        Instant now = Instant.now();
        String from = now.minusSeconds(60).toString();
        String to   = now.plusSeconds(60).toString();

        String body = mvc.perform(get("/api/admin/monitoring/gpu-cost")
                        .param("from", from)
                        .param("to", to)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestCount").value(0))
                .andExpect(jsonPath("$.totalCostUsd").value(0.0))
                .andExpect(jsonPath("$.avgCostUsd").value(0.0))
                .andReturn().getResponse().getContentAsString();

        JsonNode node = mapper.readTree(body);
        assertEquals(0, node.get("requestCount").asLong());
        assertEquals(0.0, node.get("totalCostUsd").asDouble(), DELTA);
        assertEquals(0.0, node.get("avgCostUsd").asDouble(), DELTA);
    }

    // ── 4. HTTP endpoint: correct math end-to-end ────────────────────────────

    @Test
    void gpuCost_endpoint_correctMath() throws Exception {
        String phone = uniquePhone();
        String token = registerAndGetToken(phone);
        makeSuperAdmin(phone);

        repo.save(event("success", 20000L, false));
        repo.save(event("success", 15000L, false));
        repo.save(event("fail",    18000L, false));   // excluded
        repo.save(event("success", null,   false));   // excluded
        repo.save(event("success", 19000L, true));    // excluded

        Instant now = Instant.now();
        String from = now.minusSeconds(60).toString();
        String to   = now.plusSeconds(60).toString();

        mvc.perform(get("/api/admin/monitoring/gpu-cost")
                        .param("from", from)
                        .param("to", to)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestCount").value(2))
                .andExpect(jsonPath("$.totalCostUsd").value(0.01071))
                .andExpect(jsonPath("$.avgCostUsd").value(0.005355));
    }

    // ── 5. HTTP endpoint auth/authz ───────────────────────────────────────────

    @Test
    void gpuCost_endpoint_noAuth_401() throws Exception {
        mvc.perform(get("/api/admin/monitoring/gpu-cost")
                        .param("from", Instant.now().minusSeconds(60).toString())
                        .param("to",   Instant.now().plusSeconds(60).toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void gpuCost_endpoint_normalClient_403() throws Exception {
        String phone = uniquePhone();
        String token = registerAndGetToken(phone);

        mvc.perform(get("/api/admin/monitoring/gpu-cost")
                        .param("from", Instant.now().minusSeconds(60).toString())
                        .param("to",   Instant.now().plusSeconds(60).toString())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
