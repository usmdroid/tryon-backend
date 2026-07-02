package uz.tryon.api.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import uz.tryon.api.wallet.CreditTransaction;
import uz.tryon.api.wallet.CreditTransactionRepository;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moderation endpoint RBAC + state-transition tests.
 * Follows the AdminRbacTest pattern: register+login, setRoleInDb to elevate, seed a transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ModerationTest {

    @Autowired MockMvc mvc;
    @Autowired ClientRepository clients;
    @Autowired CreditTransactionRepository txRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456";
    private static final AtomicInteger SEQ = new AtomicInteger(800);

    private String uniquePhone() {
        return "+99894" + SEQ.getAndIncrement() + "00001";
    }

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private record RegResult(String token, String phone) { }

    private RegResult registerAndLogin(String phone) throws Exception {
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", phone.replaceAll("\\D", "") + "@test.uz"))).andExpect(status().isOk());
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "T", "phone", phone,
                                "email", phone.replaceAll("\\D", "") + "@test.uz",
                                "password", "parol123", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        return new RegResult(node.get("token").asText(), phone);
    }

    private void setRoleInDb(String phone, String role) {
        Client c = clients.findByPhone(Phones.normalize(phone)).orElseThrow();
        c.setRole(role);
        clients.save(c);
    }

    private UUID seedTransaction(RegResult owner) {
        UUID clientId = clients.findByPhone(Phones.normalize(owner.phone())).orElseThrow().getId();
        CreditTransaction tx = new CreditTransaction(clientId, -1000L, "TRYON_DEBIT", 99000L, null);
        return txRepo.save(tx).getId();
    }

    // ─── CLIENT → 403 on all moderation endpoints ───────────────────────────

    @Test
    void client_list_403() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        mvc.perform(get("/api/admin/moderation").header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void client_hide_403() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(r);
        mvc.perform(post("/api/admin/moderation/" + txId + "/hide")
                        .header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void client_flag_403() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(r);
        mvc.perform(post("/api/admin/moderation/" + txId + "/flag")
                        .header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void client_restore_403() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(r);
        mvc.perform(post("/api/admin/moderation/" + txId + "/restore")
                        .header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isForbidden());
    }

    // ─── SUPER_ADMIN → 200 + status changes ─────────────────────────────────

    @Test
    void superAdmin_list_200() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        setRoleInDb(admin.phone(), "SUPER_ADMIN");

        mvc.perform(get("/api/admin/moderation").header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.total").isNumber())
                .andExpect(jsonPath("$.limit").value(50))
                .andExpect(jsonPath("$.offset").value(0));
    }

    @Test
    void superAdmin_hide_changesStatus() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        setRoleInDb(admin.phone(), "SUPER_ADMIN");
        RegResult owner = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(owner);

        mvc.perform(post("/api/admin/moderation/" + txId + "/hide")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(txId.toString()))
                .andExpect(jsonPath("$.moderationStatus").value("HIDDEN"));

        // Verify persisted
        assertEquals("HIDDEN", txRepo.findById(txId).orElseThrow().getModerationStatus());
    }

    @Test
    void superAdmin_flag_changesStatus() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        setRoleInDb(admin.phone(), "SUPER_ADMIN");
        RegResult owner = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(owner);

        mvc.perform(post("/api/admin/moderation/" + txId + "/flag")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("FLAGGED"));

        assertEquals("FLAGGED", txRepo.findById(txId).orElseThrow().getModerationStatus());
    }

    @Test
    void superAdmin_restore_changesStatus() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        setRoleInDb(admin.phone(), "SUPER_ADMIN");
        RegResult owner = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(owner);

        // First hide, then restore
        mvc.perform(post("/api/admin/moderation/" + txId + "/hide")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/moderation/" + txId + "/restore")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("VISIBLE"));

        assertEquals("VISIBLE", txRepo.findById(txId).orElseThrow().getModerationStatus());
    }

    @Test
    void superAdmin_hideToFlagToRestore_transitions() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        setRoleInDb(admin.phone(), "SUPER_ADMIN");
        RegResult owner = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(owner);

        mvc.perform(post("/api/admin/moderation/" + txId + "/hide")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("HIDDEN"));

        mvc.perform(post("/api/admin/moderation/" + txId + "/flag")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("FLAGGED"));

        mvc.perform(post("/api/admin/moderation/" + txId + "/restore")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("VISIBLE"));
    }

    @Test
    void superAdmin_unknownId_404() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        setRoleInDb(admin.phone(), "SUPER_ADMIN");

        mvc.perform(post("/api/admin/moderation/00000000-0000-0000-0000-000000000999/hide")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isNotFound());
    }

    // ─── MODERATOR → 200 (same permissions as super-admin for moderation) ───

    @Test
    void moderator_list_200() throws Exception {
        RegResult mod = registerAndLogin(uniquePhone());
        setRoleInDb(mod.phone(), "MODERATOR");

        mvc.perform(get("/api/admin/moderation").header("Authorization", "Bearer " + mod.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void moderator_hideAndFlag_200() throws Exception {
        RegResult mod = registerAndLogin(uniquePhone());
        setRoleInDb(mod.phone(), "MODERATOR");
        RegResult owner = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(owner);

        mvc.perform(post("/api/admin/moderation/" + txId + "/hide")
                        .header("Authorization", "Bearer " + mod.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("HIDDEN"));

        mvc.perform(post("/api/admin/moderation/" + txId + "/flag")
                        .header("Authorization", "Bearer " + mod.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moderationStatus").value("FLAGGED"));
    }

    @Test
    void list_filterFlagged_returnsOnlyFlagged() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        setRoleInDb(admin.phone(), "SUPER_ADMIN");
        RegResult owner = registerAndLogin(uniquePhone());
        UUID txId = seedTransaction(owner);

        // Flag it
        mvc.perform(post("/api/admin/moderation/" + txId + "/flag")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk());

        // Filter flagged — the newly flagged tx must appear
        String body = mvc.perform(get("/api/admin/moderation?filter=flagged")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode root = mapper.readTree(body);
        boolean found = false;
        for (JsonNode item : root.get("items")) {
            if (txId.toString().equals(item.get("id").asText())) {
                assertEquals("FLAGGED", item.get("moderationStatus").asText());
                found = true;
            }
        }
        org.junit.jupiter.api.Assertions.assertTrue(found, "Newly flagged tx must appear in filter=flagged result");
    }
}
