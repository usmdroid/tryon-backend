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

/** /api/auth: send-otp + register (OTP bilan) + login testlari. Test profilida OTP = "123456". */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456"; // application-test.yml: otp-fixed-code

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private void sendOtp(String phone) throws Exception {
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("phone", phone))).andExpect(status().isOk());
    }

    @Test
    void otp_yuborish_200() throws Exception {
        sendOtp("+998901110001");
    }

    @Test
    void register_otp_bilan_200() throws Exception {
        sendOtp("+998901110002");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "ATLAS", "phone", "+998901110002", "email", "atlas2@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.client.phone").value("+998901110002"));
    }

    @Test
    void register_notogri_kod_400() throws Exception {
        sendOtp("+998901110003");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A", "phone", "+998901110003", "email", "a3@dokon.uz", "password", "parol123", "code", "000000")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_kodsiz_400() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A", "phone", "+998901110004", "email", "a4@dokon.uz", "password", "parol123")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_qisqa_parol_400() throws Exception {
        sendOtp("+998901110005");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A", "phone", "+998901110005", "email", "a5@dokon.uz", "password", "123", "code", OTP)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_takror_telefon_409() throws Exception {
        sendOtp("+998901110006");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("name", "A", "phone", "+998901110006", "email", "a6@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk());
        sendOtp("+998901110006");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A", "phone", "+998901110006", "email", "a6-boshqa@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isConflict());
    }

    @Test
    void register_takror_email_409() throws Exception {
        sendOtp("+998901110009");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("name", "A", "phone", "+998901110009", "email", "takror@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk());
        // Boshqa telefon, lekin o'sha email — 409 bo'lishi kerak.
        sendOtp("+998901110010");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A", "phone", "+998901110010", "email", "takror@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isConflict());
    }

    @Test
    void register_emailsiz_400() throws Exception {
        sendOtp("+998901110011");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A", "phone", "+998901110011", "password", "parol123", "code", OTP)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_notogri_email_400() throws Exception {
        sendOtp("+998901110012");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "A", "phone", "+998901110012", "email", "notogri-email", "password", "parol123", "code", OTP)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void login_telefon_bilan_200() throws Exception {
        sendOtp("+998901110007");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("name", "A", "phone", "+998901110007", "email", "a7@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("identifier", "+998901110007", "password", "parol123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_email_bilan_200() throws Exception {
        sendOtp("+998901110013");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("name", "A", "phone", "+998901110013", "email", "a13@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("identifier", "a13@dokon.uz", "password", "parol123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_notogri_parol_401() throws Exception {
        sendOtp("+998901110008");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("name", "A", "phone", "+998901110008", "email", "a8@dokon.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json("identifier", "+998901110008", "password", "boshqa999")))
                .andExpect(status().isUnauthorized());
    }
}
