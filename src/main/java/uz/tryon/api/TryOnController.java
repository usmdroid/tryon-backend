package uz.tryon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.auth.ApiKeyService;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.wallet.CreditService;

import java.util.Map;
import java.util.UUID;

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
    private final ApiKeyService apiKeyService;
    private final CreditService creditService;
    private final StorageService storageService;
    private final ClientRepository clientRepository;

    public TryOnController(AppConfig config, RateLimiterService rateLimiter,
                           ImageValidator validator, ImageCheckService checkService,
                           ModalClient modal, TokenService tokenService,
                           ApiKeyService apiKeyService, CreditService creditService,
                           StorageService storageService, ClientRepository clientRepository) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.validator = validator;
        this.checkService = checkService;
        this.modal = modal;
        this.tokenService = tokenService;
        this.apiKeyService = apiKeyService;
        this.creditService = creditService;
        this.storageService = storageService;
        this.clientRepository = clientRepository;
    }

    /**
     * Sessiya tokenini zarb qiladi — buni DO'KON SERVERI chaqiradi (server-server, sk_ bilan).
     * Token brauzerga beriladi va /api/tryon da Bearer sifatida ishlatiladi.
     *
     * Header: X-Api-Key: <sk_>  ·  Javob: { "token": "...", "expiresIn": 300 }
     */
    @PostMapping("/session")
    public ResponseEntity<?> session(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (apiKey == null) {
            return err(HttpStatus.UNAUTHORIZED, "API kalit noto'g'ri yoki yo'q.");
        }
        // DB da ro'yxatdan o'tgan kalitlar — real UUID subject
        var dbKey = apiKeyService.findActiveByRawKey(apiKey);
        if (dbKey.isPresent()) {
            // To'xtatilgan (SUSPENDED) mijoz token ololmaydi.
            if (isSuspended(dbKey.get().getClientId().toString())) {
                return err(HttpStatus.FORBIDDEN, "Hisobingiz to'xtatilgan. Iltimos, qo'llab-quvvatlash xizmatiga murojaat qiling.");
            }
            // Foydalanishni shu kalitga bog'lash uchun kalit id'sini token ichiga kiritamiz.
            TokenService.Issued issued = tokenService.mint(
                    dbKey.get().getClientId().toString(), dbKey.get().getId().toString());
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", issued.token(), "expiresIn", issued.expiresInSeconds()));
        }
        // Legacy config kalitlar
        if (config.getApiKeys() != null && config.getApiKeys().contains(apiKey)) {
            TokenService.Issued issued = tokenService.mint(tokenService.clientId(apiKey));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("token", issued.token(), "expiresIn", issued.expiresInSeconds()));
        }
        return err(HttpStatus.UNAUTHORIZED, "API kalit noto'g'ri yoki yo'q.");
    }

    /**
     * So'rovni autentifikatsiya qiladi — Bearer token (afzal) yoki X-Api-Key (moslik uchun).
     * Yaroqli bo'lsa clientId (+ token ichidagi apiKeyId, mavjud bo'lsa) qaytaradi, aks holda null.
     * @param consumeToken Bearer token bir martalik ishlatilsinmi (qimmat amal — /tryon uchun true).
     */
    private TokenService.Verified authenticate(String apiKey, String authHeader, boolean consumeToken) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return tokenService.verifyDetailed(authHeader.substring(7).trim(), consumeToken).orElse(null);
        }
        if (apiKey != null && config.getApiKeys() != null && config.getApiKeys().contains(apiKey)) {
            // Legacy config kalitlar DB'da yo'q — kalit id'si yo'q (null).
            return new TokenService.Verified(tokenService.clientId(apiKey), null);
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
        TokenService.Verified verified = authenticate(apiKey, auth, true);
        if (verified == null) {
            return err(HttpStatus.UNAUTHORIZED, "Token yoki API kalit noto'g'ri, muddati o'tgan yoki ishlatilgan.");
        }
        String clientId = verified.clientId();

        // To'xtatilgan (SUSPENDED) mijoz so'rov yubora olmaydi.
        if (isSuspended(clientId)) {
            return err(HttpStatus.FORBIDDEN, "Hisobingiz to'xtatilgan. Iltimos, qo'llab-quvvatlash xizmatiga murojaat qiling.");
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

        // 5. Kredit tekshiruvi va yechish (faqat DB orqali ro'yxatdan o'tgan mijozlar uchun)
        UUID clientUUID = tryParseUUID(clientId);
        if (clientUUID != null) {
            // Token ichidan kelgan API kalit id'si (mavjud bo'lsa) foydalanishni shu kalitga bog'laydi.
            UUID apiKeyUUID = tryParseUUID(verified.apiKeyId());
            try {
                creditService.debitForTryOn(clientUUID, apiKeyUUID);
            } catch (CreditService.InsufficientCreditsException e) {
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("error", "insufficient_credits",
                                     "message", "Hisobingizda yetarli sim mavjud emas"));
            }
        }

        // 6. Modal'ga uzatish
        ModalClient.Result result = modal.generate(person, cloth, clothType);
        if (!result.ok()) {
            return err(HttpStatus.BAD_GATEWAY, result.error());
        }

        // 7. Natijani R2'ga yuklash — fon ipida, javobni kechiktirmaydi (faqat ma'lum mijozlar uchun)
        if (clientUUID != null) {
            storageService.uploadAsync(result.image(), clientUUID);
        }

        // 8. Natija rasmni qaytarish (WebP)
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
        TokenService.Verified verified = authenticate(apiKey, auth, false);
        if (verified == null) {
            // null = autentifikatsiya muvaffaqiyatsiz
            return err(HttpStatus.UNAUTHORIZED, "Token yoki API kalit noto'g'ri yoki muddati o'tgan.");
        }
        // To'xtatilgan (SUSPENDED) mijoz tekshiruv qila olmaydi.
        if (isSuspended(verified.clientId())) {
            return err(HttpStatus.FORBIDDEN, "Hisobingiz to'xtatilgan. Iltimos, qo'llab-quvvatlash xizmatiga murojaat qiling.");
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

    private static UUID tryParseUUID(String s) {
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * clientId UUID bo'lsa va shu mijoz SUSPENDED bo'lsa — true.
     * Legacy (UUID bo'lmagan, config kalitiga asoslangan) clientId — tekshirilmaydi (false).
     */
    private boolean isSuspended(String clientId) {
        UUID uuid = tryParseUUID(clientId);
        if (uuid == null) return false;
        return clientRepository.findById(uuid)
                .map(Client::getStatus)
                .filter("SUSPENDED"::equals)
                .isPresent();
    }
}
