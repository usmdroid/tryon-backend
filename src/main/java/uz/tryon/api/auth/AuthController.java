package uz.tryon.api.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hamkor autentifikatsiyasi (dashboard uchun).
 *   POST /api/auth/register  { name, email, password }
 *   POST /api/auth/login     { email, password }
 * Javob: { token, client: { id, name, email } }
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final OtpService otp;
    private final uz.tryon.api.AppConfig config;

    public AuthController(AuthService auth, OtpService otp, uz.tryon.api.AppConfig config) {
        this.auth = auth;
        this.otp = otp;
        this.config = config;
    }

    public record SendOtpRequest(String email) { }
    public record RegisterRequest(String name, String phone, String email, String password, String code) { }
    public record LoginRequest(String identifier, String password) { }

    // Email formati uchun oddiy, ishonchli tekshiruv: lokal@domen.tld
    private static final java.util.regex.Pattern EMAIL_RX =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    /** Email manzilga tasdiqlash kodi yuboradi (registratsiyadan oldin). */
    @PostMapping("/send-otp")
    public ResponseEntity<?> sendOtp(@RequestBody SendOtpRequest req) {
        if (isBlank(req.email())) {
            return err(HttpStatus.BAD_REQUEST, "Email kiritilishi shart.");
        }
        // Email formatini tekshiramiz (lokal@domen.tld).
        if (!EMAIL_RX.matcher(req.email().trim()).matches()) {
            return err(HttpStatus.BAD_REQUEST, "Email formati noto'g'ri.");
        }
        try {
            String code = otp.sendCode(req.email());
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("message", "Tasdiqlash kodi yuborildi.");
            if (config.isOtpExposeCode()) body.put("devCode", code); // faqat dev flag yoqilganda
            return ResponseEntity.ok(body);
        } catch (OtpService.TooSoonException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, "Kod yaqinda yuborilgan. Biroz kuting.");
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (isBlank(req.name()) || isBlank(req.phone()) || isBlank(req.password())) {
            return err(HttpStatus.BAD_REQUEST, "Do'kon nomi, telefon va parol to'ldirilishi shart.");
        }
        // Email endi majburiy — bo'sh bo'lsa rad etamiz.
        if (isBlank(req.email())) {
            return err(HttpStatus.BAD_REQUEST, "Email to'ldirilishi shart.");
        }
        // Email formatini tekshiramiz (lokal@domen.tld).
        if (!EMAIL_RX.matcher(req.email().trim()).matches()) {
            return err(HttpStatus.BAD_REQUEST, "Email formati noto'g'ri.");
        }
        if (req.password().length() < 6) {
            return err(HttpStatus.BAD_REQUEST, "Parol kamida 6 belgidan iborat bo'lsin.");
        }
        try {
            if (!otp.verify(req.email(), req.code())) {
                return err(HttpStatus.BAD_REQUEST, "Tasdiqlash kodi noto'g'ri yoki muddati o'tgan.");
            }
        } catch (OtpService.BlockedException e) {
            return err(HttpStatus.TOO_MANY_REQUESTS, blockedMessage(e));
        }
        try {
            Client c = auth.register(req.name(), req.phone(), req.email(), req.password());
            return ok(c);
        } catch (AuthService.PhoneAlreadyExistsException e) {
            return err(HttpStatus.CONFLICT, "Bu telefon raqam allaqachon ro'yxatdan o'tgan.");
        } catch (AuthService.EmailAlreadyExistsException e) {
            return err(HttpStatus.CONFLICT, "Bu email allaqachon ro'yxatdan o'tgan.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (isBlank(req.identifier()) || isBlank(req.password())) {
            return err(HttpStatus.BAD_REQUEST, "Telefon/email va parol to'ldirilishi shart.");
        }
        try {
            Client c = auth.login(req.identifier(), req.password());
            return ok(c);
        } catch (AuthService.InvalidCredentialsException e) {
            return err(HttpStatus.UNAUTHORIZED, "Telefon/email yoki parol noto'g'ri.");
        }
    }

    private ResponseEntity<?> ok(Client c) {
        Map<String, String> client = new java.util.HashMap<>();
        client.put("id", c.getId().toString());
        client.put("name", c.getName());
        client.put("phone", c.getPhone());
        client.put("email", c.getEmail()); // null bo'lishi mumkin — HashMap ruxsat beradi
        client.put("role", c.getRole());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("token", auth.issueSessionToken(c), "client", client));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Bloklash xabari — qancha kutish kerakligini (daqiqa/soniya) o'zbekcha bildiradi. */
    private static String blockedMessage(OtpService.BlockedException e) {
        long secs = e.getRemainingSeconds();
        if (secs >= 60) {
            long mins = (secs + 59) / 60; // yuqoriga yaxlitlab daqiqa
            return "Juda ko'p urinish. " + mins + " daqiqadan so'ng qayta urinib ko'ring.";
        }
        return "Juda ko'p urinish. " + secs + " soniyadan so'ng qayta urinib ko'ring.";
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
