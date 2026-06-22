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
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OTP suiiste'mol lockout HTTP testi (MockMvc).
 *
 * Test profilida otp-fixed-code=123456 (devMode). devMode'da resend-cooldown breach
 * o'tkazib yuboriladi, lekin verify() har bir NOTO'G'RI kod uchun breach yozadi.
 * OtpAbuseGuard.BREACH_THRESHOLD = 3 (1 daqiqalik oyna) → blok. AuthController
 * BlockedException ni HTTP 429 ga maplaydi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OtpLockoutHttpTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    @Test
    void register_uchinchi_notogri_kod_429() throws Exception {
        int n = SEQ.getAndIncrement();
        String email = "lockout" + n + "@dokon.uz";
        String phone = "+99893" + n + "0000001";

        // 1) Kod yuborish — 200.
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email)))
                .andExpect(status().isOk());

        // 2) Birinchi 2 ta noto'g'ri kod — 400 (bad request), breach to'planadi.
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(json("name", "L", "phone", phone, "email", email,
                                    "password", "parol123", "code", "000000")))
                    .andExpect(status().isBadRequest());
        }

        // 3) Uchinchi noto'g'ri urinish — blok (429), xabarda kutish matni bo'ladi.
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "L", "phone", phone, "email", email,
                                "password", "parol123", "code", "000000")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(containsString("Juda ko'p urinish")));
    }
}
