package uz.tryon.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** /api/auth/register + /api/auth/login testlari (telefon/email + parol). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private String body(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private void register(String b) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isOk());
    }

    @Test
    void register_yangi_200_token() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("name", "ATLAS", "phone", "+998901112233", "password", "parol123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.client.phone").value("+998901112233"));
    }

    @Test
    void register_takror_telefon_409() throws Exception {
        String b = body("name", "A", "phone", "+998901112244", "password", "parol123");
        register(b);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isConflict());
    }

    @Test
    void register_qisqa_parol_400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("name", "A", "phone", "+998901112255", "password", "123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_telefonsiz_400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(body("name", "A", "password", "parol123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_telefon_bilan_200() throws Exception {
        register(body("name", "A", "phone", "+998901112266", "password", "parol123"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("identifier", "+998901112266", "password", "parol123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_email_bilan_200() throws Exception {
        register(body("name", "A", "phone", "+998901112277", "email", "log@dokon.uz", "password", "parol123"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("identifier", "log@dokon.uz", "password", "parol123")))
                .andExpect(status().isOk());
    }

    @Test
    void login_notogri_parol_401() throws Exception {
        register(body("name", "A", "phone", "+998901112288", "password", "parol123"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body("identifier", "+998901112288", "password", "boshqa999")))
                .andExpect(status().isUnauthorized());
    }
}
