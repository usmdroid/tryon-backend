package uz.tryon.api.connect;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.tryon.api.auth.ApiKey;
import uz.tryon.api.auth.ApiKeyRepository;
import uz.tryon.api.auth.ApiKeyService;
import uz.tryon.api.auth.AuthService;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * OAuth-uslubida kalit ulash (CMS plaginlari uchun).
 *   POST /api/connect/authorize  — Bearer session token bilan, {code} qaytaradi.
 *   POST /api/connect/exchange   — auth'siz (server-server), {code} → {key}.
 */
@RestController
@RequestMapping("/api/connect")
public class ConnectController {

    /** Kod amal qilish muddati (5 daqiqa). */
    private static final long CODE_TTL_SECONDS = 5 * 60;

    private final AuthService authService;
    private final ApiKeyRepository apiKeyRepo;
    private final ApiKeyService apiKeyService;
    private final ConnectCodeRepository connectCodeRepo;

    public ConnectController(AuthService authService,
                             ApiKeyRepository apiKeyRepo,
                             ApiKeyService apiKeyService,
                             ConnectCodeRepository connectCodeRepo) {
        this.authService = authService;
        this.apiKeyRepo = apiKeyRepo;
        this.apiKeyService = apiKeyService;
        this.connectCodeRepo = connectCodeRepo;
    }

    public record AuthorizeRequest(String apiKeyId, String redirectUri, String state) { }
    public record ExchangeRequest(String code) { }

    /**
     * Bir martalik kod yaratadi.
     * Foydalanuvchi kalit tanlaydi → frontend shu endpoint'ni chaqiradi → {code} → redirect_uri'ga redirect.
     */
    @PostMapping("/authorize")
    public ResponseEntity<?> authorize(@RequestBody AuthorizeRequest req, HttpServletRequest request) {
        // Session token tekshirish
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());

        // apiKeyId majburiy
        if (req.apiKeyId() == null || req.apiKeyId().isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "apiKeyId majburiy.");
        }
        UUID apiKeyId;
        try {
            apiKeyId = UUID.fromString(req.apiKeyId());
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "apiKeyId noto'g'ri format.");
        }

        // Kalit mavjud va foydalanuvchiga tegishlimi?
        Optional<ApiKey> keyOpt = apiKeyRepo.findByIdAndClientId(apiKeyId, clientId);
        if (keyOpt.isEmpty()) {
            return err(HttpStatus.NOT_FOUND, "API kalit topilmadi.");
        }
        ApiKey apiKey = keyOpt.get();

        // Bekor qilingan yoki eski kalit (keyEnc=null) bo'lsa tanlab bo'lmaydi
        if (apiKey.getRevokedAt() != null || apiKey.getKeyEnc() == null) {
            return err(HttpStatus.BAD_REQUEST, "Bu kalitni ulab bo'lmaydi.");
        }

        // redirect_uri tekshirish: https (yoki localhost http)
        if (req.redirectUri() == null || req.redirectUri().isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "redirectUri majburiy.");
        }
        if (!isAllowedRedirectUri(req.redirectUri())) {
            return err(HttpStatus.BAD_REQUEST, "redirectUri https bilan boshlanishi shart (localhost bundan mustasno).");
        }

        // Bir martalik kodni yaratib saqlaymiz (hash ko'rinishida)
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String codeHash = sha256hex(code);
        Instant expiresAt = Instant.now().plusSeconds(CODE_TTL_SECONDS);

        connectCodeRepo.save(new ConnectCode(codeHash, clientId, apiKeyId, req.redirectUri(), expiresAt));

        return ResponseEntity.ok(Map.of("code", code));
    }

    /**
     * Kodni tekshirib, kalit qaytaradi (server-server, brauzerga chiqmaydi).
     * Kod bir martalik va TTL'ga ega.
     */
    @PostMapping("/exchange")
    @Transactional
    public ResponseEntity<?> exchange(@RequestBody ExchangeRequest req) {
        if (req.code() == null || req.code().isBlank()) {
            return err(HttpStatus.BAD_REQUEST, "Kod noto'g'ri yoki muddati o'tgan.");
        }

        String codeHash = sha256hex(req.code());

        // Kodni topamiz
        Optional<ConnectCode> recordOpt = connectCodeRepo.findByCodeHash(codeHash);
        if (recordOpt.isEmpty()) {
            return err(HttpStatus.BAD_REQUEST, "Kod noto'g'ri yoki muddati o'tgan.");
        }
        ConnectCode record = recordOpt.get();

        // TTL tekshirish
        if (Instant.now().isAfter(record.getExpiresAt())) {
            return err(HttpStatus.BAD_REQUEST, "Kod noto'g'ri yoki muddati o'tgan.");
        }

        // Atomik consume (bir martalik): 0 qaytarsa — allaqachon ishlatilgan
        int updated = connectCodeRepo.consumeByHash(codeHash, Instant.now());
        if (updated == 0) {
            return err(HttpStatus.BAD_REQUEST, "Kod noto'g'ri yoki muddati o'tgan.");
        }

        // Kalitni ochib qaytaramiz (faqat server-server!)
        Optional<ApiKey> keyOpt = apiKeyRepo.findById(record.getApiKeyId());
        if (keyOpt.isEmpty()) {
            return err(HttpStatus.BAD_REQUEST, "Kalit topilmadi.");
        }
        String secret = apiKeyService.revealKey(keyOpt.get());
        if (secret == null) {
            return err(HttpStatus.BAD_REQUEST, "Kalit ochib bo'lmadi.");
        }

        return ResponseEntity.ok(Map.of("key", secret));
    }

    /** Bearer token'dan clientId oladi. */
    private Optional<String> authenticate(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        return authService.verifySessionToken(header.substring(7));
    }

    /**
     * redirect_uri faqat https yoki localhost/127.0.0.1 http bo'lishi mumkin.
     * Open-redirect va kod o'g'irlashdan himoya.
     */
    private static boolean isAllowedRedirectUri(String uriStr) {
        try {
            URI uri = URI.create(uriStr);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return false;
            if ("https".equalsIgnoreCase(scheme)) return true;
            if ("http".equalsIgnoreCase(scheme)) {
                return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return err(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }

    private static String sha256hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
