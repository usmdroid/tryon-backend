package uz.tryon.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Sessiya tokeni oqimi testlari (/api/session + Bearer). */
@SpringBootTest
@AutoConfigureMockMvc
class TokenFlowTest {

    @Autowired MockMvc mvc;
    @Autowired TokenService tokenService;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String API_KEY = "test-key-12345";

    private String mintToken() throws Exception {
        String json = mvc.perform(post("/api/session").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(json);
        assertTrue(node.has("token"));
        return node.get("token").asText();
    }

    @Test
    void session_kalitsiz_401() throws Exception {
        mvc.perform(post("/api/session")).andExpect(status().isUnauthorized());
    }

    @Test
    void session_token_beradi() throws Exception {
        assertFalse(mintToken().isBlank());
    }

    @Test
    void check_bearerToken_bilan_ishlaydi() throws Exception {
        String token = mintToken();
        mvc.perform(post("/api/check")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("person_image", "", "cloth_type", "upper"))))
                .andExpect(status().isOk()); // auth o'tdi (ichida format fail bo'lsa ham 200)
    }

    @Test
    void check_yaroqsizToken_401() throws Exception {
        mvc.perform(post("/api/check")
                        .header("Authorization", "Bearer not.a.real.token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("person_image", "", "cloth_type", "upper"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void token_birMartali_consume() {
        TokenService.Issued issued = tokenService.mint(API_KEY);
        // 1-marta consume → ok
        assertTrue(tokenService.verify(issued.token(), true).isPresent());
        // 2-marta consume → rad (bir martali)
        assertTrue(tokenService.verify(issued.token(), true).isEmpty());
    }

    @Test
    void token_soxta_imzo_rad() {
        TokenService.Issued issued = tokenService.mint(API_KEY);
        String tampered = issued.token().substring(0, issued.token().indexOf('.')) + ".SOXTA_IMZO";
        assertTrue(tokenService.verify(tampered, false).isEmpty());
    }
}
