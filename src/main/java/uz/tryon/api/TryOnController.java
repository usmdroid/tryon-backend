package uz.tryon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Asosiy endpoint: POST /api/tryon
 *
 * So'rov oqimi (tartib muhim — GPU'ga yetguncha filtrlash):
 *   1. API kalit tekshiruvi (header)
 *   2. Origin (domain) allowlist
 *   3. Rate limit
 *   4. Rasm validatsiyasi
 *   5. Modal'ga uzatish (ichki secret bilan)
 *   6. Natijani qaytarish
 *
 * Kirish (JSON): { person_image: base64, cloth_image: base64, cloth_type: "upper" }
 * Header: X-Api-Key: <sotuvchi kaliti>
 * Chiqish: image/webp (muvaffaqiyat) yoki JSON xato
 */
@RestController
@RequestMapping("/api")
public class TryOnController {

    private final AppConfig config;
    private final RateLimiterService rateLimiter;
    private final ImageValidator validator;
    private final ModalClient modal;

    public TryOnController(AppConfig config, RateLimiterService rateLimiter,
                           ImageValidator validator, ModalClient modal) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.modal = modal;
    }

    @PostMapping("/tryon")
    public ResponseEntity<?> tryOn(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestBody Map<String, String> payload) {

        // 1. API kalit
        if (apiKey == null || config.getApiKeys() == null || !config.getApiKeys().contains(apiKey)) {
            return err(HttpStatus.UNAUTHORIZED, "API kalit noto'g'ri yoki yo'q.");
        }

        // 2. Origin (domain) allowlist — agar ro'yxat bo'sh bo'lmasa tekshiramiz
        if (config.getAllowedOrigins() != null && !config.getAllowedOrigins().isEmpty()) {
            if (origin == null || !config.getAllowedOrigins().contains(origin)) {
                return err(HttpStatus.FORBIDDEN, "Bu domendan so'rovga ruxsat yo'q.");
            }
        }

        // 3. Rate limit
        if (!rateLimiter.allow(apiKey)) {
            return err(HttpStatus.TOO_MANY_REQUESTS, "So'rovlar chegarasi oshdi. Birozdan keyin urinib ko'ring.");
        }

        // 4. Rasm validatsiyasi (ikkala rasm)
        String person = payload.get("person_image");
        String cloth = payload.get("cloth_image");
        String clothType = payload.getOrDefault("cloth_type", "upper");

        ImageValidator.Result pv = validator.validate(person);
        if (!pv.ok()) return err(HttpStatus.BAD_REQUEST, "Shaxs rasmi: " + pv.reason());

        ImageValidator.Result cv = validator.validate(cloth);
        if (!cv.ok()) return err(HttpStatus.BAD_REQUEST, "Kiyim rasmi: " + cv.reason());

        // 5. Modal'ga uzatish
        ModalClient.Result result = modal.generate(person, cloth, clothType);
        if (!result.ok()) {
            return err(HttpStatus.BAD_GATEWAY, result.error());
        }

        // 6. Natija rasmni qaytarish (WebP)
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/webp"))
                .body(result.image());
    }

    /** Soddagina sog'liq tekshiruvi (deploy platformasi uchun). */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
