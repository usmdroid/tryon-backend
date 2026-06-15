package uz.tryon.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** /api/auth/register + /api/auth/login testlari (H2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private String body(Object... kv) throws Exception {
        var m = new java.util.HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    @Test
    void register_yangi_200_token() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("name", "ATLAS", "email", "yangi@dokon.uz", "password", "parol123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.client.email").value("yangi@dokon.uz"));
    }

    @Test
    void register_takror_email_409() throws Exception {
        String b = body("name", "A", "email", "takror@dokon.uz", "password", "parol123");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isConflict());
    }

    @Test
    void register_qisqa_parol_400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("name", "A", "email", "qisqa@dokon.uz", "password", "123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_togri_200() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body("name", "A", "email", "login@dokon.uz", "password", "parol123")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("email", "login@dokon.uz", "password", "parol123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_notogri_parol_401() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(body("name", "A", "email", "xato@dokon.uz", "password", "parol123")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("email", "xato@dokon.uz", "password", "boshqa999")))
                .andExpect(status().isUnauthorized());
    }
}
