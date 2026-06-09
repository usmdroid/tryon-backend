package uz.tryon.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * POST /api/check uchun integratsiya testlari (Modal'ga tegmaydi).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CheckEndpointTest {

    @Autowired
    MockMvc mvc;

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
}
