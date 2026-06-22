package uz.tryon.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * USM-6 E2E test: API Keys feature (sima-backend layer).
 * Registers two clients and verifies create/list/revoke + auth + client scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456";

    private String tokenA;  // client A
    private String tokenB;  // client B

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private String registerAndLogin(String phone) throws Exception {
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", phone.replaceAll("\\D", "") + "@test.uz"))).andExpect(status().isOk());
        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "User " + phone, "phone", phone,
                                "email", phone.replaceAll("\\D", "") + "@test.uz",
                                "password", "parol123", "code", OTP)))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> body = mapper.readValue(r.getResponse().getContentAsString(), Map.class);
        return (String) body.get("token");
    }

    @BeforeAll
    void setup() throws Exception {
        tokenA = registerAndLogin("+998900000101");
        tokenB = registerAndLogin("+998900000102");
    }

    // -----------------------------------------------------------------------
    // Auth guard: all 3 routes must reject missing / invalid bearer
    // -----------------------------------------------------------------------

    @Test
    void auth_list_no_token_401() throws Exception {
        mvc.perform(get("/api/api-keys"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auth_create_no_token_401() throws Exception {
        mvc.perform(post("/api/api-keys").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "test")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auth_delete_no_token_401() throws Exception {
        mvc.perform(delete("/api/api-keys/00000000-0000-0000-0000-000000000001"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auth_list_bad_token_401() throws Exception {
        mvc.perform(get("/api/api-keys")
                        .header("Authorization", "Bearer not-a-valid-token"))
                .andExpect(status().isUnauthorized());
    }

    // -----------------------------------------------------------------------
    // POST /api/api-keys — create: 201, full secret once, prefix sk_
    // -----------------------------------------------------------------------

    @Test
    void create_returns_201_and_secret_once() throws Exception {
        mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "My first key")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("My first key"))
                .andExpect(jsonPath("$.key").value(startsWith("sk_")))
                .andExpect(jsonPath("$.keyPrefix").isNotEmpty())
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void create_blank_name_400() throws Exception {
        mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "  ")))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // GET /api/api-keys — list: masked prefix only, no hash/secret
    // -----------------------------------------------------------------------

    @Test
    void list_shows_prefix_never_hash() throws Exception {
        // Create a key first
        mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "List-test key")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[*].name", hasItem("List-test key")))
                .andExpect(jsonPath("$[*].keyPrefix").isNotEmpty())
                // Full secret must NEVER appear in list response
                .andExpect(jsonPath("$[*].key").doesNotExist())
                .andExpect(jsonPath("$[*].keyHash").doesNotExist());
    }

    // -----------------------------------------------------------------------
    // DELETE /api/api-keys/{id} — revoke; shows revokedAt in list
    // -----------------------------------------------------------------------

    @Test
    void revoke_sets_revoked_at() throws Exception {
        // Create
        MvcResult cr = mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Revoke-test key")))
                .andExpect(status().isCreated()).andReturn();
        String id = (String) mapper.readValue(cr.getResponse().getContentAsString(), Map.class).get("id");

        // Revoke
        mvc.perform(delete("/api/api-keys/" + id)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        // List — revokedAt must now be non-null
        mvc.perform(get("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id=='" + id + "')].revokedAt", not(hasItem(nullValue()))));
    }

    // -----------------------------------------------------------------------
    // Client scoping: client B cannot list or revoke client A's keys
    // -----------------------------------------------------------------------

    @Test
    void client_scoping_list_isolation() throws Exception {
        // Client A creates a key
        mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A-only key")))
                .andExpect(status().isCreated());

        // Client B's list must NOT contain client A's key
        String listB = mvc.perform(get("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assert !listB.contains("A-only key") : "Client B can see Client A's key! Scoping broken.";
    }

    @Test
    void client_scoping_revoke_cross_client_404() throws Exception {
        // Client A creates a key
        MvcResult cr = mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A-key for cross test")))
                .andExpect(status().isCreated()).andReturn();
        String id = (String) mapper.readValue(cr.getResponse().getContentAsString(), Map.class).get("id");

        // Client B tries to revoke Client A's key → 404
        mvc.perform(delete("/api/api-keys/" + id)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }
}
