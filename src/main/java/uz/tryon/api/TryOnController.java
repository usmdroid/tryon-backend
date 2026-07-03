package uz.tryon.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.admin.TryonFlagService;
import uz.tryon.api.auth.ApiKeyService;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.devsandbox.DevSandboxKeyService;
import uz.tryon.api.telemetry.TryOnEventService;
import uz.tryon.api.util.AuthHashUtils;
import uz.tryon.api.wallet.CreditService;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
    private final TryOnEventService tryOnEventService;
    private final DevSandboxKeyService devSandboxKeyService;
    private final TryonFlagService tryonFlagService;

    public TryOnController(AppConfig config, RateLimiterService rateLimiter,
                           ImageValidator validator, ImageCheckService checkService,
                           ModalClient modal, TokenService tokenService,
                           ApiKeyService apiKeyService, CreditService creditService,
                           StorageService storageService, ClientRepository clientRepository,
                           TryOnEventService tryOnEventService,
                           DevSandboxKeyService devSandboxKeyService,
                           TryonFlagService tryonFlagService) {
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
        this.tryOnEventService = tryOnEventService;
        this.devSandboxKeyService = devSandboxKeyService;
        this.tryonFlagService = tryonFlagService;
    }

    /**
     * Sessiya tokenini zarb qiladi — buni DO'KON SERVERI chaqiradi (server-server, sk_ bilan).
     * Token brauzerga beriladi va /api/tryon da Bearer sifatida ishlatiladi.
     *
     * Header: X-Api-Key: <sk_>  ·  Javob: { "token": "...", "expiresIn": 300 }
     */
    @PostMapping("/session")
    public ResponseEntity<?> session(@RequestHeader(value = "X-Api-Key", required = false) String apiKey) {
        if (config.isMaintenance()) return maintenance();
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
            HttpServletRequest request,
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Origin", required = false) String origin,
            @RequestHeader(value = "X-Sima-Platform", required = false) String platform,
            @RequestHeader(value = "X-Sima-Device-Id", required = false) String deviceId,
            @RequestHeader(value = "X-Marketplace-Token", required = false) String marketplaceToken,
            @RequestHeader(value = "X-Sima-Emulator", required = false) String emulatorHeader,
            @RequestBody Map<String, String> payload) {

        long startMs = System.currentTimeMillis();
        if (config.isMaintenance()) return maintenance();
        if (!tryonFlagService.isEnabled()) return tryonDisabled();

        // 1. Autentifikatsiya — Bearer token (bir martali) yoki X-Api-Key
        TokenService.Verified verified = authenticate(apiKey, auth, true);
        if (verified == null) {
            return err(HttpStatus.UNAUTHORIZED, "Token yoki API kalit noto'g'ri, muddati o'tgan yoki ishlatilgan.");
        }
        String clientId = verified.clientId();

        // Dev sandbox token detection: clientId encoded as "dev:{devKeyId}"
        boolean isDevToken = clientId.startsWith("dev:");
        UUID devKeyId = null;
        if (isDevToken) {
            devKeyId = tryParseUUID(clientId.substring(4));
            if (devKeyId == null) {
                return err(HttpStatus.UNAUTHORIZED, "Token yoki API kalit noto'g'ri, muddati o'tgan yoki ishlatilgan.");
            }
        }

        // SUSPENDED check only applies to regular partner accounts
        if (!isDevToken && isSuspended(clientId)) {
            return err(HttpStatus.FORBIDDEN, "Hisobingiz to'xtatilgan. Iltimos, qo'llab-quvvatlash xizmatiga murojaat qiling.");
        }

        // 2. Origin va partnerId aniqlash
        final OriginContext ctx;
        if (isDevToken) {
            ctx = new OriginContext("dev_sandbox", devKeyId);
        } else {
            ctx = resolveOrigin(verified, marketplaceToken);
        }

        // 3. Origin (domain) allowlist — dev sandbox tokens bypass domain check
        if (!isDevToken && config.getAllowedOrigins() != null && !config.getAllowedOrigins().isEmpty()) {
            if (origin == null || !config.getAllowedOrigins().contains(origin)) {
                return err(HttpStatus.FORBIDDEN, "Bu domendan so'rovga ruxsat yo'q.");
            }
        }

        // 4. Rate limit (clientId bo'yicha)
        if (!rateLimiter.allow(clientId)) {
            return err(HttpStatus.TOO_MANY_REQUESTS, "So'rovlar chegarasi oshdi. Birozdan keyin urinib ko'ring.");
        }

        // 5. Rasm validatsiyasi (ikkala rasm)
        String person = payload.get("person_image");
        String cloth = payload.get("cloth_image");
        String clothType = payload.getOrDefault("cloth_type", "upper");
        String productId = payload.get("product_id");
        if (productId == null || productId.isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "product_id majburiy maydon.");
        }
        String productName = payload.get("product_name");

        String clientIp = resolveClientIp(request);

        ImageValidator.Result pv = validator.validate(person);
        if (!pv.ok()) return err(HttpStatus.BAD_REQUEST, "Shaxs rasmi: " + pv.reason());

        ImageValidator.Result cv = validator.validate(cloth);
        if (!cv.ok()) return err(HttpStatus.BAD_REQUEST, "Kiyim rasmi: " + cv.reason());

        // 6. Kredit tekshiruvi va yechish
        //    - marketplace kanali uchun o'tkazib yuboriladi
        //    - dev sandbox uchun o'tkazib yuboriladi (used_count atomic increment below)
        UUID clientUUID = isDevToken ? null : tryParseUUID(clientId);
        boolean isMarketplace = ctx != null && "marketplace".equals(ctx.origin());
        if (clientUUID != null && !isMarketplace) {
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

        // 6b. Dev sandbox: atomically increment used_count (race-safe conditional UPDATE)
        if (isDevToken) {
            if (!devSandboxKeyService.tryIncrement(devKeyId)) {
                return err(HttpStatus.PAYMENT_REQUIRED, "dev limit tugadi, yangi kalit oling");
            }
        }

        // 7. Modal'ga uzatish
        long gpuStartNs = System.nanoTime();
        ModalClient.Result result = modal.generate(person, cloth, clothType);
        long gpuMs = (System.nanoTime() - gpuStartNs) / 1_000_000L;

        // 8. Telemetry voqeasini yozish (partner_id ma'lum bo'lsa — har ikki natija uchun)
        if (ctx != null) {
            String effectivePlatform = (platform != null && !platform.isBlank())
                    ? platform.substring(0, Math.min(platform.length(), 16)) : "web";
            String safeDeviceId = deviceId != null && deviceId.length() > 64
                    ? deviceId.substring(0, 64) : deviceId;
            long durationMs = System.currentTimeMillis() - startMs;
            String safeProductName = productName != null && productName.length() > 255
                    ? productName.substring(0, 255) : productName;
            boolean isEmulator = "true".equalsIgnoreCase(emulatorHeader);
            tryOnEventService.record(
                    effectivePlatform, ctx.origin(), ctx.partnerId(), safeDeviceId,
                    productId, safeProductName, clothType,
                    result.ok() ? "success" : "fail",
                    result.ok() ? null : truncate(result.error(), 255),
                    durationMs, clientIp, isEmulator, gpuMs);
        }

        if (!result.ok()) {
            return err(HttpStatus.BAD_GATEWAY, result.error());
        }

        // 9. Natijani R2'ga yuklash — fon ipida, javobni kechiktirmaydi (faqat ma'lum mijozlar uchun)
        if (clientUUID != null) {
            storageService.uploadAsync(result.image(), clientUUID);
        }

        // 10. Natija rasmni qaytarish (WebP)
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
     * X-Sima-Platform, X-Sima-Device-Id, X-Marketplace-Token headerlari qabul qilinadi, e'tiborsiz qoldiriladi.
     */
    @PostMapping("/check")
    public ResponseEntity<?> check(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, String> payload) {

        if (config.isMaintenance()) return maintenance();
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

    /** Texnik tanaffus rejimi — rasm endpoint'lari uchun. */
    private ResponseEntity<Map<String, String>> maintenance() {
        return err(HttpStatus.SERVICE_UNAVAILABLE,
                "Xizmat texnik tanaffusda. Iltimos, biroz keyin urinib ko'ring.");
    }

    /** TRYON_ENABLED = false — GPU kill switch faol. */
    private ResponseEntity<Map<String, String>> tryonDisabled() {
        return err(HttpStatus.SERVICE_UNAVAILABLE,
                "Kiyib ko'rish xizmati vaqtincha to'xtatilgan. Tez orada qayta ishga tushadi.");
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

    /** Kanalning manba va partnerId kontekstini ifodalaydi. */
    private record OriginContext(String origin, UUID partnerId) {}

    /**
     * So'rov kanalini aniqlaydi: marketplace yoki partner_site.
     * Marketplace tokenni tekshiradi; aks holda clientId bo'yicha partner_site deb belgilanadi.
     */
    private OriginContext resolveOrigin(TokenService.Verified verified, String marketplaceTokenHeader) {
        if (marketplaceTokenHeader != null && !config.getMarketplaceSecret().isBlank()) {
            UUID partnerId = verifyMarketplaceToken(marketplaceTokenHeader);
            if (partnerId != null) {
                return new OriginContext("marketplace", partnerId);
            }
        }
        UUID clientUUID = tryParseUUID(verified.clientId());
        if (clientUUID != null) {
            return new OriginContext("partner_site", clientUUID);
        }
        return null;
    }

    /**
     * Marketplace tokenini tekshiradi va partnerId qaytaradi; yaroqsiz bo'lsa null.
     * Token formati: base64url("marketplace|{partnerId}|{expMs}").base64url(hmac-sha256(secret, payload))
     */
    private UUID verifyMarketplaceToken(String token) {
        if (token == null || token.isBlank()) return null;
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return null;
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String sig = token.substring(dot + 1);
        if (!AuthHashUtils.constantTimeEquals(
                sig, AuthHashUtils.b64Url(AuthHashUtils.hmacSha256(config.getMarketplaceSecret(), payload)))) {
            return null;
        }
        String[] f = payload.split("\\|", 3);
        if (f.length != 3 || !"marketplace".equals(f[0])) return null;
        try {
            long exp = Long.parseLong(f[2]);
            if (System.currentTimeMillis() > exp) return null;
            return UUID.fromString(f[1]);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max);
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
