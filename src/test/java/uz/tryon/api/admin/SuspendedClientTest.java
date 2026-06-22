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
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.auth.Phones;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Suspend enforcement: to'xtatilgan mijoz /api/check (va /api/session) dan 403 oladi.
 * Rol/holat har so'rovda DB dan o'qiladi — token bekor qilinmasa ham bloklanadi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SuspendedClientTest {

    @Autowired MockMvc mvc;
    @Autowired ClientRepository clients;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456";
    private static final AtomicInteger SEQ = new AtomicInteger(300);

    private String uniquePhone() {
        return "+99892" + SEQ.getAndIncrement() + "00001";
    }

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private String registerAndLogin(String phone) throws Exception {
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", phone.replaceAll("\\D", "") + "@test.uz"))).andExpect(status().isOk());
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "T", "phone", phone, "email", phone.replaceAll("\\D", "") + "@test.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    @Test
    void suspendedClient_check_403() throws Exception {
        String phone = uniquePhone();
        String sessionToken = registerAndLogin(phone);

        // Dashboard sessiyasi orqali API kalit yaratamiz
        String keyBody = mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + sessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "suspend-test")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String rawKey = mapper.readTree(keyBody).get("key").asText();

        // /api/session orqali bir martali bearer token olamiz (hali ACTIVE)
        String sessBody = mvc.perform(post("/api/session").header("X-Api-Key", rawKey))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sess = mapper.readTree(sessBody);
        String widgetToken = sess.get("token").asText();

        // Mijozni SUSPENDED qilamiz (DB to'g'ridan-to'g'ri)
        var c = clients.findByPhone(Phones.normalize(phone)).orElseThrow();
        c.setStatus("SUSPENDED");
        clients.save(c);

        // Endi /api/check (consume=false) — holat DB dan o'qiladi -> 403
        mvc.perform(post("/api/check")
                        .header("Authorization", "Bearer " + widgetToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("person_image", "x", "cloth_type", "upper")))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendedClient_session_403() throws Exception {
        String phone = uniquePhone();
        String sessionToken = registerAndLogin(phone);

        String keyBody = mvc.perform(post("/api/api-keys")
                        .header("Authorization", "Bearer " + sessionToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "suspend-session-test")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String rawKey = mapper.readTree(keyBody).get("key").asText();

        // Suspend qilamiz
        var c = clients.findByPhone(Phones.normalize(phone)).orElseThrow();
        c.setStatus("SUSPENDED");
        clients.save(c);

        // /api/session — to'xtatilgan mijoz token ololmaydi -> 403
        mvc.perform(post("/api/session").header("X-Api-Key", rawKey))
                .andExpect(status().isForbidden());
    }
}
