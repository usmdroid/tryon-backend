package uz.tryon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Asosiy endpoint: POST /api/tryon
 * <p>
 * So'rov oqimi (tartib muhim — GPU'ga yetguncha filtrlash):
 *   1. API kalit tekshiruvi (header)
 *   2. Origin (domain) allowlist
 *   3. Rate limit
 *   4. Rasm validatsiyasi
 *   5. Modal'ga uzatish (ichki secret bilan)
 *   6. Natijani qaytarish
 * <p>
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
    private final ImageCheckService checkService;
    private final ModalClient modal;
    private final TokenService tokenService;

    public TryOnController(AppConfig config, RateLimiterService rateLimiter,
                           ImageValidator validator, ImageCheckService checkService,
                           ModalClient modal, TokenService tokenService) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.checkService = checkService;
        this.modal = modal;
        this.tokenService = tokenService;
    }

    /**
     * Sessiya tokenini zarb qiladi — buni DO'KON SERVERI chaqiradi (server-server, sk_ bilan).
     * Token brauzerga beriladi va /api/tryon da Bearer sifatida ishlatiladi.
     *
     * Header: X-Api-Key: <sk_>  ·  Javob: { "token": "...", "expiresIn": 300 }
     */
    @PostMapping("/session")
    public ResponseEntity<?> session(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (apiKey == null || config.getApiKeys() == null || !config.getApiKeys().contains(apiKey)) {
            return err(HttpStatus.UNAUTHORIZED, "API kalit noto'g'ri yoki yo'q.");
        }
        TokenService.Issued issued = tokenService.mint(apiKey);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", issued.token(), "expiresIn", issued.expiresInSeconds()));
    }

    /**
     * So'rovni autentifikatsiya qiladi — Bearer token (afzal) yoki X-Api-Key (moslik uchun).
     * Yaroqli bo'lsa clientId qaytaradi (rate-limit/identifikatsiya uchun), aks holda null.
     * @param consumeToken Bearer token bir martalik ishlatilsinmi (qimmat amal — /tryon uchun true).
     */
    private String authenticate(String apiKey, String authHeader, boolean consumeToken) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return tokenService.verify(authHeader.substring(7).trim(), consumeToken).orElse(null);
        }
        if (apiKey != null && config.getApiKeys() != null && config.getApiKeys().contains(apiKey)) {
            return tokenService.clientId(apiKey);
        }
        return null;
    }

    @PostMapping("/tryon")
    public ResponseEntity<?> tryOn(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestBody Map<String, String> payload) {

        // 1. Autentifikatsiya — Bearer token (bir martali) yoki X-Api-Key
        String clientId = authenticate(apiKey, auth, true);
        if (clientId == null) {
            return err(HttpStatus.UNAUTHORIZED, "Token yoki API kalit noto'g'ri, muddati o'tgan yoki ishlatilgan.");
        }

        // 2. Origin (domain) allowlist — agar ro'yxat bo'sh bo'lmasa tekshiramiz
        if (config.getAllowedOrigins() != null && !config.getAllowedOrigins().isEmpty()) {
            if (origin == null || !config.getAllowedOrigins().contains(origin)) {
                return err(HttpStatus.FORBIDDEN, "Bu domendan so'rovga ruxsat yo'q.");
            }
        }

        // 3. Rate limit (clientId bo'yicha)
        if (!rateLimiter.allow(clientId)) {
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

    /**
     * Rasm tekshiruvi — Modal'ga (GPU'ga) TEGMASDAN, rasm generatsiyaga yaroqliligini baholaydi.
     * Frontenddagi "Tekshirish" tugmasi shuni chaqiradi; javobni log/Toast'ga aylantiradi.
     * <p>
     * Kirish (JSON): { person_image: base64, cloth_type: "upper" }
     * Header: X-Api-Key: <sotuvchi kaliti>
     * Chiqish: CheckReport JSON (ok, checks[], summary).
     * <p>
     * GPU xarajati yo'q, shuning uchun rate limit qo'llanmaydi (faqat API kalit tekshiriladi).
     */
    @PostMapping("/check")
    public ResponseEntity<?> check(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, String> payload) {

        // Bearer token (consume QILMAYDI — arzon amal) yoki X-Api-Key
        if (authenticate(apiKey, auth, false) == null) {
            return err(HttpStatus.UNAUTHORIZED, "Token yoki API kalit noto'g'ri yoki muddati o'tgan.");
        }

        String person = payload.get("person_image");
        String clothType = payload.getOrDefault("cloth_type", "upper");

        CheckReport report = checkService.check(person, clothType);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(report);
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
