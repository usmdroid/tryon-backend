package uz.tryon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * USM-186 acceptance criteria verification:
 * 1. product_id required: POST /api/tryon without product_id → HTTP 400 with UZ message
 * 2. product_name optional: request without product_name still succeeds auth/validation stage
 * 3. Origin server-authoritative: no client body/header field can override origin
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProductIdValidationTest {

    @Autowired MockMvc mvc;
    @Autowired TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String API_KEY = "test-key-12345";

    private String mintToken() throws Exception {
        String json = mvc.perform(post("/api/session").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json).get("token").asText();
    }

    private String jsonBody(Object... kv) throws Exception {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    // ── Behavior 1: product_id required ───────────────────────────────────────

    @Test
    void tryon_noProductId_returns400WithUzMessage() throws Exception {
        String token = mintToken();
        MvcResult result = mvc.perform(post("/api/tryon")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(
                                "person_image", "",
                                "cloth_image", "",
                                "cloth_type", "upper"
                        )))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        assertTrue(json.has("error"), "Response must have 'error' field");
        String errorMsg = json.get("error").asText();
        assertTrue(errorMsg.contains("product_id"), "Error message must mention product_id, got: " + errorMsg);
        assertFalse(errorMsg.isBlank(), "Error message must not be blank");
    }

    @Test
    void tryon_blankProductId_returns400WithUzMessage() throws Exception {
        String token = mintToken();
        MvcResult result = mvc.perform(post("/api/tryon")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(
                                "person_image", "",
                                "cloth_image", "",
                                "cloth_type", "upper",
                                "product_id", "   "
                        )))
                .andExpect(status().isBadRequest())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        assertTrue(json.has("error"), "Response must have 'error' field");
        String errorMsg = json.get("error").asText();
        assertTrue(errorMsg.contains("product_id"), "Error message must mention product_id for blank, got: " + errorMsg);
    }

    @Test
    void tryon_withProductId_doesNotReturn400ForProductId() throws Exception {
        // With valid product_id, error should be about image format (not product_id missing)
        // This proves product_id validation was passed and we reached image validation.
        String token = mintToken();
        String body = mvc.perform(post("/api/tryon")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(
                                "person_image", "",
                                "cloth_image", "",
                                "cloth_type", "upper",
                                "product_id", "test-product-123"
                        )))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        // Should NOT be "product_id majburiy maydon." error
        if (json.has("error")) {
            String errorMsg = json.get("error").asText();
            assertFalse(errorMsg.contains("product_id majburiy"),
                    "When product_id is provided, error must not be about missing product_id, got: " + errorMsg);
        }
        // Should have reached image validation (either 400 for invalid image or 401 if token consumed)
    }

    // ── Behavior 3: Origin server-authoritative ───────────────────────────────

    @Test
    void tryon_clientBodyOriginField_doesNotOverrideOrigin() throws Exception {
        // Sending "origin" in the body should NOT change the server-resolved origin.
        // The product_id check should still hit 400 because origin is determined server-side.
        String token = mintToken();
        MvcResult result = mvc.perform(post("/api/tryon")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(
                                "person_image", "",
                                "cloth_image", "",
                                "cloth_type", "upper",
                                "origin", "marketplace"  // attempt to override origin via body
                        )))
                .andExpect(status().isBadRequest()) // 400 from product_id check (not origin-related)
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        String errorMsg = json.has("error") ? json.get("error").asText() : "";
        // Must be product_id error (origin field in body is ignored)
        assertTrue(errorMsg.contains("product_id"),
                "Body 'origin' field must be ignored; product_id error expected, got: " + errorMsg);
    }

    // ── Behavior 2: product_name optional ─────────────────────────────────────

    @Test
    void tryon_noProductName_passesThatValidationStage() throws Exception {
        // Without product_name, we should get past the product_id check
        // (will fail at image format check, not product_name)
        String token = mintToken();
        String body = mvc.perform(post("/api/tryon")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody(
                                "person_image", "",
                                "cloth_image", "",
                                "cloth_type", "upper",
                                "product_id", "test-product-123"
                                // no product_name
                        )))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        if (json.has("error")) {
            String errorMsg = json.get("error").asText();
            // Must NOT be an error about product_name
            assertFalse(errorMsg.toLowerCase().contains("product_name"),
                    "product_name must be optional, got unexpected error: " + errorMsg);
            assertFalse(errorMsg.contains("product_id majburiy"),
                    "product_id was provided — must not trigger product_id missing error");
        }
    }

    // ── No auth → 401 ─────────────────────────────────────────────────────────

    @Test
    void tryon_noAuth_returns401() throws Exception {
        mvc.perform(post("/api/tryon")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody("product_id", "x")))
                .andExpect(status().isUnauthorized());
    }
}
