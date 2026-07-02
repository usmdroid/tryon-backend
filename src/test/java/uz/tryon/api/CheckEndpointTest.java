package uz.tryon.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * POST /api/check uchun integratsiya testlari (Modal'ga tegmaydi).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CheckEndpointTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ImageCheckService imageCheckService;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String API_KEY = "test-key-12345";

    /** Tasodifiy "shovqinli" PNG yaratadi — blur dispersiyasi yuqori bo'lsin (aniq deb topilsin). */
    private String noisyPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int seed = 12345;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Oddiy deterministik psevdo-shovqin (Math.random ishlatmaymiz — test barqaror bo'lsin)
                seed = seed * 1103515245 + 12345;
                int v = (seed >>> 16) & 0xFF;
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    private String body(String personB64, String clothType) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "person_image", personB64 == null ? "" : personB64,
                "cloth_type", clothType));
    }

    @Test
    void apiKeysiz_401() throws Exception {
        mvc.perform(post("/api/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "upper")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shaxssizRasm_sifatPass_yuzTopilmadi() throws Exception {
        // Tasodifiy shovqinli rasm: sifat tekshiruvlari o'tadi, lekin odam/yuz yo'q → face_count fail
        mvc.perform(post("/api/check")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(noisyPng(300, 300), "upper")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clothType").value("upper"))
                .andExpect(jsonPath("$.checks[?(@.id=='resolution')].status").value("pass"))
                .andExpect(jsonPath("$.checks[?(@.id=='brightness')].status").value("pass"))
                .andExpect(jsonPath("$.checks[?(@.id=='face_count')].status").value("fail"))
                .andExpect(jsonPath("$.ok").value(false));
    }

    @Test
    void juentaKichikRasm_okFalse_failFast() throws Exception {
        // Rezolyutsiya fail bo'lsa, ketma-ketlik shu yerda to'xtaydi:
        // odam soni / poza tekshiruvlari UMUMAN bo'lmasligi kerak (fail-fast).
        mvc.perform(post("/api/check")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(noisyPng(64, 64), "upper")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.checks[?(@.id=='resolution')].status").value("fail"))
                .andExpect(jsonPath("$.checks[?(@.id=='face_count')]").isEmpty())
                .andExpect(jsonPath("$.checks[?(@.id=='pose')]").isEmpty());
    }

    @Test
    void rasmsiz_formatFail() throws Exception {
        mvc.perform(post("/api/check")
                        .header("X-Api-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("", "upper")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.checks[?(@.id=='format')].status").value("fail"));
    }

    // ---- Poza: ko'p signalli orqa aniqlash birlik testlari ----

    /** 17 ta keypoint massivini yasaydi. Barcha qiymatlar 0 dan boshlanadi. */
    private static PoseDetector.Keypoint[] buildKp() {
        PoseDetector.Keypoint[] kp = new PoseDetector.Keypoint[17];
        for (int i = 0; i < 17; i++) kp[i] = new PoseDetector.Keypoint(0.5, 0.5, 0.0);
        return kp;
    }

    /** Tik turganda yelka va bel koordinatalarini belgilaydi (yotmagan tekshiruvi o'tsin). */
    private static PoseDetector.Person standingPerson(PoseDetector.Keypoint[] kp,
                                                       double lShScore, double rShScore) {
        kp[PoseDetector.L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, lShScore);
        kp[PoseDetector.R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, rShScore);
        kp[PoseDetector.L_HIP]      = new PoseDetector.Keypoint(0.60, 0.40, 0.80);
        kp[PoseDetector.R_HIP]      = new PoseDetector.Keypoint(0.60, 0.60, 0.80);
        return new PoseDetector.Person(kp, 0.90);
    }

    @Test
    void frontKeypoints_passesPoseCheck() {
        PoseDetector.Keypoint[] kp = buildKp();
        // Kuchli yuz signallari (old tomondan)
        kp[PoseDetector.NOSE]  = new PoseDetector.Keypoint(0.10, 0.50, 0.90);
        kp[PoseDetector.L_EYE] = new PoseDetector.Keypoint(0.10, 0.45, 0.90);
        kp[PoseDetector.R_EYE] = new PoseDetector.Keypoint(0.10, 0.55, 0.90);
        kp[PoseDetector.L_EAR] = new PoseDetector.Keypoint(0.10, 0.40, 0.10);
        kp[PoseDetector.R_EAR] = new PoseDetector.Keypoint(0.10, 0.60, 0.10);
        PoseDetector.Person person = standingPerson(kp, 0.80, 0.80);

        // detectFacing: votes=0 → FRONT
        ImageCheckService.Facing facing = ImageCheckService.detectFacing(kp, 0.5, 0.4, 0.3, 0.3);
        assertThat(facing).isEqualTo(ImageCheckService.Facing.FRONT);

        CheckItem result = imageCheckService.poseCheck(person, 0.30);
        assertThat(result.status()).isEqualTo(CheckItem.PASS);
        assertThat(result.id()).isEqualTo("pose");
    }

    @Test
    void backKeypoints_failsPoseCheckWithPoseCode() {
        PoseDetector.Keypoint[] kp = buildKp();
        // Zaif yuz, kuchli quloqlar (orqa tomondan)
        kp[PoseDetector.NOSE]  = new PoseDetector.Keypoint(0.10, 0.50, 0.10);
        kp[PoseDetector.L_EYE] = new PoseDetector.Keypoint(0.10, 0.45, 0.10);
        kp[PoseDetector.R_EYE] = new PoseDetector.Keypoint(0.10, 0.55, 0.10);
        kp[PoseDetector.L_EAR] = new PoseDetector.Keypoint(0.10, 0.40, 0.80);
        kp[PoseDetector.R_EAR] = new PoseDetector.Keypoint(0.10, 0.60, 0.80);
        PoseDetector.Person person = standingPerson(kp, 0.80, 0.80);

        // detectFacing: 3 ovoz → BACK
        ImageCheckService.Facing facing = ImageCheckService.detectFacing(kp, 0.5, 0.4, 0.3, 0.3);
        assertThat(facing).isEqualTo(ImageCheckService.Facing.BACK);

        CheckItem result = imageCheckService.poseCheck(person, 0.30);
        assertThat(result.status()).isEqualTo(CheckItem.FAIL);
        assertThat(result.id()).isEqualTo("pose");
    }

    @Test
    void sideKeypoints_warnsPoseCheck() {
        PoseDetector.Keypoint[] kp = buildKp();
        // Kuchli yuz (orqa ovozlari yo'q), lekin faqat bir yelka ko'rinadi
        kp[PoseDetector.NOSE]  = new PoseDetector.Keypoint(0.10, 0.50, 0.90);
        kp[PoseDetector.L_EYE] = new PoseDetector.Keypoint(0.10, 0.45, 0.90);
        kp[PoseDetector.R_EYE] = new PoseDetector.Keypoint(0.10, 0.55, 0.90);
        kp[PoseDetector.L_EAR] = new PoseDetector.Keypoint(0.10, 0.40, 0.10);
        kp[PoseDetector.R_EAR] = new PoseDetector.Keypoint(0.10, 0.60, 0.10);
        // Faqat chap yelka ko'rinadi (o'ng yelka score < kmin=0.30)
        PoseDetector.Person person = standingPerson(kp, 0.80, 0.05);

        CheckItem result = imageCheckService.poseCheck(person, 0.30);
        assertThat(result.status()).isEqualTo(CheckItem.WARN);
        assertThat(result.id()).isEqualTo("pose");
    }
}
