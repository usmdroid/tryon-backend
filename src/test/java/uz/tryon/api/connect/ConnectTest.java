package uz.tryon.api.connect;

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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * USM-30 E2E test: Connect oqimi — authorize + exchange.
 * Ro'yxatdan o'tish → API kalit yaratish → authorize → exchange.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectTest {

    @Autowired MockMvc mvc;
    @Autowired ConnectCodeRepository connectCodeRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String OTP = "123456";
    private static final String REDIRECT = "https://example.com/callback";

    private String token;
    private UUID clientId;
    private String apiKeyId;

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    @BeforeAll
    void setup() throws Exception {
        String phone = "+998902230100";
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", "connect-test@dokon.uz"))).andExpect(status().isOk());

        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Connect Test", "phone", phone,
                                "email", "connect-test@dokon.uz",
                                "password", "parol123", "code", OTP)))
                .andExpect(status().isOk()).andReturn();

        Map<?, ?> auth = mapper.readValue(r.getResponse().getContentAsString(), Map.class);
        token = (String) auth.get("token");
        clientId = UUID.fromString((String) ((Map<?, ?>) auth.get("client")).get("id"));

        MvcResult kr = mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Connect kalit")))
                .andExpect(status().isCreated()).andReturn();
        apiKeyId = (String) mapper.readValue(kr.getResponse().getContentAsString(), Map.class).get("id");
    }

    // --- authorize: auth tekshiruvi ---

    @Test
    void authorize_token_yoq_401() throws Exception {
        mvc.perform(post("/api/connect/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", apiKeyId, "redirectUri", REDIRECT, "state", null)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void authorize_token_noto_gri_401() throws Exception {
        mvc.perform(post("/api/connect/authorize")
                        .header("Authorization", "Bearer haqiqiy-emas-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", apiKeyId, "redirectUri", REDIRECT, "state", null)))
                .andExpect(status().isUnauthorized());
    }

    // --- authorize: kalit validatsiyasi ---

    @Test
    void authorize_notogri_key_id_404() throws Exception {
        mvc.perform(post("/api/connect/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", UUID.randomUUID().toString(), "redirectUri", REDIRECT, "state", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void authorize_bekor_qilingan_kalit_400() throws Exception {
        // Yangi kalit yaratib bekor qilamiz
        MvcResult kr = mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Bekor kalit")))
                .andExpect(status().isCreated()).andReturn();
        String revokeId = (String) mapper.readValue(kr.getResponse().getContentAsString(), Map.class).get("id");

        mvc.perform(delete("/api/api-keys/" + revokeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mvc.perform(post("/api/connect/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", revokeId, "redirectUri", REDIRECT, "state", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void authorize_notogri_redirect_uri_400() throws Exception {
        mvc.perform(post("/api/connect/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", apiKeyId, "redirectUri", "ftp://bad.example.com", "state", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorize_localhost_http_ruxsat() throws Exception {
        mvc.perform(post("/api/connect/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", apiKeyId, "redirectUri", "http://localhost:8080/cb", "state", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty());
    }

    // --- authorize → exchange: muvaffaqiyatli oqim ---

    @Test
    void authorize_exchange_bajaradi_sk_kalit_qaytaradi() throws Exception {
        MvcResult ar = mvc.perform(post("/api/connect/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", apiKeyId, "redirectUri", REDIRECT, "state", "xyz")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andReturn();

        String code = (String) mapper.readValue(ar.getResponse().getContentAsString(), Map.class).get("code");

        mvc.perform(post("/api/connect/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(startsWith("sk_")));
    }

    // --- exchange: bir martalik himoya ---

    @Test
    void exchange_ikki_marta_400() throws Exception {
        MvcResult ar = mvc.perform(post("/api/connect/authorize")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("apiKeyId", apiKeyId, "redirectUri", REDIRECT, "state", null)))
                .andExpect(status().isOk()).andReturn();
        String code = (String) mapper.readValue(ar.getResponse().getContentAsString(), Map.class).get("code");

        mvc.perform(post("/api/connect/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", code)))
                .andExpect(status().isOk());

        // Ikkinchi urinish rad etiladi
        mvc.perform(post("/api/connect/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // --- exchange: muddati o'tgan kod ---

    @Test
    void exchange_muddati_otgan_400() throws Exception {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String codeHash = sha256hex(code);

        // Muddati o'tgan holda to'g'ridan-to'g'ri saqlaydi
        connectCodeRepo.save(new ConnectCode(
                codeHash,
                clientId,
                UUID.fromString(apiKeyId),
                REDIRECT,
                Instant.now().minusSeconds(3600) // 1 soat oldin muddati o'tgan
        ));

        mvc.perform(post("/api/connect/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", code)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // --- exchange: noto'g'ri kod ---

    @Test
    void exchange_notogri_kod_400() throws Exception {
        mvc.perform(post("/api/connect/exchange").contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "bu-noto-gri-kod")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    private static String sha256hex(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
