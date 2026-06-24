package uz.tryon.api.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * USM-72: Account settings end-to-end tests.
 * Coverage: phone change, primary email change, secondary email add/verify/delete, negative cases.
 * Uses test profile (H2 in-memory, OTP fixed = "123456").
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountControllerTest {

    @Autowired MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String OTP = "123456";
    private static final AtomicInteger SEQ = new AtomicInteger(900);

    // ── helpers ──────────────────────────────────────────────────────────────

    private String json(Object... kv) throws Exception {
        var m = new HashMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return mapper.writeValueAsString(m);
    }

    /** Register a fresh user and return their session token. */
    private String registerAndToken(String emailPrefix) throws Exception {
        int n = SEQ.getAndIncrement();
        String email = emailPrefix + n + "@acct.uz";
        String phone = "+99890" + String.format("%07d", n);

        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email)))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Tester", "phone", phone, "email", email,
                                "password", "parol1234", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn();

        return mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    /** Register user with a known phone, return {token, phone, email}. */
    private String[] registerKnown(String emailSuffix, String phone) throws Exception {
        String email = "known" + emailSuffix + "@acct.uz";
        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email)))
                .andExpect(status().isOk());

        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json("name", "Tester", "phone", phone, "email", email,
                                "password", "parol1234", "code", OTP)))
                .andExpect(status().isOk())
                .andReturn();

        String token = mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();
        return new String[]{token, phone, email};
    }

    // ── Flow 1: Phone change ──────────────────────────────────────────────────

    @Test
    @DisplayName("FLOW-1a: Phone change-request returns sent=true via email channel")
    void phoneChange_request_returns_sent() throws Exception {
        String token = registerAndToken("ph1a");
        String newPhone = "+998901" + String.format("%06d", SEQ.getAndIncrement());

        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", newPhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true))
                .andExpect(jsonPath("$.channel").value("email"));
        // devCode only present when otp-expose-code=true (not set in test profile by design)
    }

    @Test
    @DisplayName("FLOW-1b: Phone change verify updates clients.phone and returns new phone")
    void phoneChange_verify_updates_phone() throws Exception {
        String token = registerAndToken("ph1b");
        String newPhone = "+998902" + String.format("%06d", SEQ.getAndIncrement());

        // request
        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", newPhone)))
                .andExpect(status().isOk());

        // verify
        mvc.perform(post("/api/account/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", OTP, "newPhone", newPhone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value(newPhone));
    }

    // ── Flow 2: Primary email change ─────────────────────────────────────────

    @Test
    @DisplayName("FLOW-2a: Email change-request sends OTP to new email, returns sent=true")
    void emailChange_request_returns_sent() throws Exception {
        String token = registerAndToken("em2a");
        String newEmail = "newemail" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newEmail", newEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
        // devCode only present when otp-expose-code=true (not set in test profile by design)
    }

    @Test
    @DisplayName("FLOW-2b: Email change verify updates clients.email")
    void emailChange_verify_updates_email() throws Exception {
        String token = registerAndToken("em2b");
        String newEmail = "changed" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newEmail", newEmail)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/account/email/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", OTP, "newEmail", newEmail)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail));
    }

    // ── Flow 3: Secondary email add / verify / delete ────────────────────────

    @Test
    @DisplayName("FLOW-3a: Add secondary email returns sent=true")
    void secondary_add_returns_sent() throws Exception {
        String token = registerAndToken("se3a");
        String sec = "sec" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", sec)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent").value(true));
        // devCode only present when otp-expose-code=true (not set in test profile by design)
    }

    @Test
    @DisplayName("FLOW-3b: Verify secondary email → verified=true, appears in GET list")
    void secondary_verify_and_list() throws Exception {
        String token = registerAndToken("se3b");
        String sec = "sec" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", sec)))
                .andExpect(status().isOk());

        // verify
        mvc.perform(post("/api/account/email/verify-secondary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", OTP, "email", sec)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.email").value(sec));

        // list
        mvc.perform(get("/api/account/email/secondary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value(sec))
                .andExpect(jsonPath("$[0].verified").value(true));
    }

    @Test
    @DisplayName("FLOW-3c: Delete secondary email → 204, no longer in list")
    void secondary_delete() throws Exception {
        String token = registerAndToken("se3c");
        String sec = "sec" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", sec)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/account/email/verify-secondary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", OTP, "email", sec)))
                .andExpect(status().isOk());

        // get the id from list
        MvcResult list = mvc.perform(get("/api/account/email/secondary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String id = mapper.readTree(list.getResponse().getContentAsString())
                .get(0).get("id").asText();

        // delete
        mvc.perform(delete("/api/account/email/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // list should be empty
        mvc.perform(get("/api/account/email/secondary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ── Negative cases ────────────────────────────────────────────────────────

    @Test
    @DisplayName("NEG-1: Unauthenticated requests return 401")
    void unauthenticated_401() throws Exception {
        mvc.perform(post("/api/account/phone/change-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", "+998901234567")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/account/email/change-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newEmail", "x@y.uz")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("NEG-2: Wrong OTP on phone verify → 400")
    void wrongOtp_phoneVerify_400() throws Exception {
        String token = registerAndToken("neg2");
        String newPhone = "+998903" + String.format("%06d", SEQ.getAndIncrement());

        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", newPhone)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/account/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "000000", "newPhone", newPhone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("NEG-3: Wrong OTP on email verify → 400")
    void wrongOtp_emailVerify_400() throws Exception {
        String token = registerAndToken("neg3");
        String newEmail = "neg3target" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newEmail", newEmail)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/account/email/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "000000", "newEmail", newEmail)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("NEG-4: Duplicate phone → 409 on change-request")
    void duplicatePhone_409() throws Exception {
        int n = SEQ.getAndIncrement();
        String existingPhone = "+998904" + String.format("%06d", n);
        // Register user B with the phone we want to steal
        registerKnown("negdup4" + n, existingPhone);

        // Register user A
        String tokenA = registerAndToken("neg4a");

        // User A tries to change to user B's phone
        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", existingPhone)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("NEG-5: Duplicate email on primary change → 409")
    void duplicateEmail_409() throws Exception {
        int n = SEQ.getAndIncrement();
        String takenPhone = "+998905" + String.format("%06d", n);
        String[] taken = registerKnown("negdup5" + n, takenPhone);
        String takenEmail = taken[2];

        String tokenA = registerAndToken("neg5a");

        mvc.perform(post("/api/account/email/change-request")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newEmail", takenEmail)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("NEG-6: Same phone as current → 400")
    void samePhone_400() throws Exception {
        int n = SEQ.getAndIncrement();
        String phone = "+998906" + String.format("%06d", n);
        String email = "same6" + n + "@acct.uz";

        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email))).andExpect(status().isOk());

        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("name", "T", "phone", phone, "email", email,
                        "password", "parol1234", "code", OTP)))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();

        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", phone)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("NEG-7: Invalid phone format → 400")
    void invalidPhone_400() throws Exception {
        String token = registerAndToken("neg7");

        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", "not-a-phone")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("NEG-8: OTP abuse → 3 wrong attempts → 429 lockout")
    void otpAbuse_429() throws Exception {
        String token = registerAndToken("neg8");
        int n = SEQ.getAndIncrement();
        String newPhone = "+998907" + String.format("%06d", n);

        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPhone", newPhone)))
                .andExpect(status().isOk());

        // 2 wrong attempts → 400
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/account/phone/verify")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json("code", "000000", "newPhone", newPhone)))
                    .andExpect(status().isBadRequest());
        }

        // 3rd wrong attempt → 429 (lockout)
        mvc.perform(post("/api/account/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "000000", "newPhone", newPhone)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", containsString("Juda ko'p urinish")));
    }

    @Test
    @DisplayName("NEG-9: DELETE /email/{id} of another client's secondary email → 404 (no IDOR)")
    void delete_idor_404() throws Exception {
        // User A adds and verifies a secondary email
        String tokenA = registerAndToken("neg9a");
        String secA = "sec9a" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", secA)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/account/email/verify-secondary")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", OTP, "email", secA)))
                .andExpect(status().isOk());

        MvcResult listA = mvc.perform(get("/api/account/email/secondary")
                        .header("Authorization", "Bearer " + tokenA))
                .andReturn();
        String idA = mapper.readTree(listA.getResponse().getContentAsString())
                .get(0).get("id").asText();

        // User B tries to delete user A's secondary email
        String tokenB = registerAndToken("neg9b");

        mvc.perform(delete("/api/account/email/" + idA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("NEG-10: Add primary email as secondary → 400")
    void addPrimaryAsSecondary_400() throws Exception {
        int n = SEQ.getAndIncrement();
        String phone = "+998908" + String.format("%06d", n);
        String email = "prim10" + n + "@acct.uz";

        mvc.perform(post("/api/auth/send-otp").contentType(MediaType.APPLICATION_JSON)
                .content(json("email", email))).andExpect(status().isOk());

        MvcResult r = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(json("name", "T", "phone", phone, "email", email,
                        "password", "parol1234", "code", OTP)))
                .andExpect(status().isOk()).andReturn();
        String token = mapper.readTree(r.getResponse().getContentAsString()).get("token").asText();

        // Attempt to add the current primary email as a secondary
        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("NEG-11: Verify secondary with wrong OTP → 400")
    void secondary_wrongOtp_400() throws Exception {
        String token = registerAndToken("neg11");
        String sec = "sec11" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", sec)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/account/email/verify-secondary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", "000000", "email", sec)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("NEG-12: Add already-verified secondary email → 409")
    void secondary_addAlreadyVerified_409() throws Exception {
        String token = registerAndToken("neg12");
        String sec = "sec12" + SEQ.getAndIncrement() + "@acct.uz";

        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", sec)))
                .andExpect(status().isOk());

        mvc.perform(post("/api/account/email/verify-secondary")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("code", OTP, "email", sec)))
                .andExpect(status().isOk());

        // Try to add same email again after it's verified
        mvc.perform(post("/api/account/email/add")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", sec)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("NEG-13: Missing body fields → 400")
    void missingFields_400() throws Exception {
        String token = registerAndToken("neg13");

        mvc.perform(post("/api/account/phone/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/account/email/change-request")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
