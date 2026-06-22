package uz.tryon.api.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.auth.Phones;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ko'p-ijarachi (multi-tenant) izolyatsiya:
 *  - Mijoz A mijoz B ning API kalitini ko'ra/o'chira olmaydi (404).
 *  - Mijoz A super-admin endpoint'iga kira olmaydi (403) — boshqa mijoz ma'lumotiga teginolmaydi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CrossTenantIsolationTest {

    @Autowired MockMvc mvc;
    @Autowired ClientRepository clients;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456";
    private static final AtomicInteger SEQ = new AtomicInteger(500);

    private String uniquePhone() {
        return "+99893" + SEQ.getAndIncrement() + "00001";
    }

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private record Reg(String token, String phone) { }

    private Reg registerAndLogin(String phone) throws Exception {
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", phone.replaceAll("\\D", "") + "@test.uz"))).andExpect(status().isOk());
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "T", "phone", phone, "email", phone.replaceAll("\\D", "") + "@test.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Reg(mapper.readTree(body).get("token").asText(), phone);
    }

    @Test
    void clientA_cannotDelete_clientB_apiKey_404() throws Exception {
        Reg a = registerAndLogin(uniquePhone());
        Reg b = registerAndLogin(uniquePhone());

        // B kalit yaratadi
        String created = mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "B-secret-key")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String keyId = (String) mapper.readValue(created, Map.class).get("id");

        // A B ning kalitini o'chirmoqchi -> 404
        mvc.perform(delete("/api/api-keys/" + keyId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isNotFound());

        // A ning ro'yxatida B ning kaliti ko'rinmaydi
        String listA = mvc.perform(get("/api/api-keys")
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertFalse(listA.contains("B-secret-key"), "Mijoz A mijoz B ning kalitini ko'rmasligi kerak");
    }

    @Test
    void normalClient_cannotReadOtherClientViaAdmin_403() throws Exception {
        Reg a = registerAndLogin(uniquePhone());
        Reg b = registerAndLogin(uniquePhone());
        String bId = clients.findByPhone(Phones.normalize(b.phone())).orElseThrow().getId().toString();

        // A (super-admin emas) admin orqali B ni ko'rmoqchi -> 403
        mvc.perform(get("/api/admin/clients/" + bId)
                        .header("Authorization", "Bearer " + a.token()))
                .andExpect(status().isForbidden());
    }
}
