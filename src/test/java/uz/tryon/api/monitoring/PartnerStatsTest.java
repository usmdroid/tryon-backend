package uz.tryon.api.monitoring;

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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** GET /api/stats/self testlari: 200 to'g'ri shakl, 401 tokensiz, 403 SUSPENDED. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PartnerStatsTest {

    @Autowired MockMvc mvc;
    @Autowired ClientRepository clients;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456";
    private static final AtomicInteger SEQ = new AtomicInteger(700);

    private String uniquePhone() {
        return "+99893" + SEQ.getAndIncrement() + "00001";
    }

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private String registerAndGetToken(String phone) throws Exception {
        String email = phone.replaceAll("\\D", "") + "@stats.uz";
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email))).andExpect(status().isOk());
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Stats User", "phone", phone,
                                "email", email, "password", "parol123", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("token").asText();
    }

    @Test
    void stats_200_with_shape() throws Exception {
        String token = registerAndGetToken(uniquePhone());

        String body = mvc.perform(get("/api/stats/self")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("last_30_days"))
                .andExpect(jsonPath("$.requests.total").isNumber())
                .andExpect(jsonPath("$.requests.success").isNumber())
                .andExpect(jsonPath("$.requests.failed").isNumber())
                .andExpect(jsonPath("$.creditsSpentSim").isNumber())
                .andExpect(jsonPath("$.balanceSim").isNumber())
                .andExpect(jsonPath("$.topKeys").isArray())
                .andExpect(jsonPath("$.recentActivity").isArray())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        assertTrue(json.get("balanceSim").asDouble() >= 0.0);
    }

    @Test
    void stats_401_without_token() throws Exception {
        mvc.perform(get("/api/stats/self"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void stats_403_suspended() throws Exception {
        String phone = uniquePhone();
        String token = registerAndGetToken(phone);

        var c = clients.findByPhone(Phones.normalize(phone)).orElseThrow();
        c.setStatus("SUSPENDED");
        clients.save(c);

        mvc.perform(get("/api/stats/self")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SUSPENDED"));
    }
}
