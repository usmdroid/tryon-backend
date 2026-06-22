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
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.auth.Phones;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC: oddiy CLIENT -> 403, SUPER_ADMIN -> 200 super-admin endpoint'larida.
 * Super-admin testda ClientRepository orqali rolni o'zgartirib yaratiladi.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminRbacTest {

    @Autowired MockMvc mvc;
    @Autowired ClientRepository clients;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456";
    private static final AtomicInteger SEQ = new AtomicInteger(100);

    private String uniquePhone() {
        return "+99891" + SEQ.getAndIncrement() + "00001";
    }

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    private record RegResult(String token, String phone) { }

    private RegResult registerAndLogin(String phone) throws Exception {
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("phone", phone))).andExpect(status().isOk());
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "T", "phone", phone, "email", phone.replaceAll("\\D", "") + "@test.uz", "password", "parol123", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = mapper.readTree(body);
        return new RegResult(node.get("token").asText(), phone);
    }

    /** Rolni DB da SUPER_ADMIN ga o'zgartiradi (telefon bo'yicha topib). */
    private void makeSuperAdmin(String phone) {
        Client c = clients.findByPhone(Phones.normalize(phone)).orElseThrow();
        c.setRole("SUPER_ADMIN");
        clients.save(c);
    }

    @Test
    void normalClient_listClients_403() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        mvc.perform(get("/api/admin/clients").header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void noToken_listClients_401() throws Exception {
        mvc.perform(get("/api/admin/clients"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void superAdmin_listClients_200() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        makeSuperAdmin(r.phone());

        String body = mvc.perform(get("/api/admin/clients")
                        .header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.startsWith("["), "javob mijozlar ro'yxati (massiv) bo'lishi kerak");
    }

    @Test
    void superAdmin_stats_200() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        makeSuperAdmin(r.phone());
        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isOk());
    }

    @Test
    void normalClient_stats_403() throws Exception {
        RegResult r = registerAndLogin(uniquePhone());
        mvc.perform(get("/api/admin/stats").header("Authorization", "Bearer " + r.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void superAdmin_creditAndSuspend_flow() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        makeSuperAdmin(admin.phone());

        // Target mijoz
        RegResult target = registerAndLogin(uniquePhone());
        String targetId = clients.findByPhone(Phones.normalize(target.phone())).orElseThrow().getId().toString();

        // Kredit qo'shish (100 boshlang'ich + 50 = 150 sim)
        mvc.perform(post("/api/admin/clients/" + targetId + "/credit")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("amountSim", 50)))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.balanceSim").value(150.0));

        // Suspend
        mvc.perform(post("/api/admin/clients/" + targetId + "/suspend")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("SUSPENDED"));

        // Activate
        mvc.perform(post("/api/admin/clients/" + targetId + "/activate")
                        .header("Authorization", "Bearer " + admin.token()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void superAdmin_creditUnknownClient_404() throws Exception {
        RegResult admin = registerAndLogin(uniquePhone());
        makeSuperAdmin(admin.phone());
        mvc.perform(post("/api/admin/clients/00000000-0000-0000-0000-000000000999/credit")
                        .header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("amountSim", 10)))
                .andExpect(status().isNotFound());
    }
}
