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

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record RegisterRequest(String name, String email, String password) { }
    public record LoginRequest(String email, String password) { }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest req) {
        if (isBlank(req.name()) || isBlank(req.email()) || isBlank(req.password())) {
            return err(HttpStatus.BAD_REQUEST, "Nom, email va parol to'ldirilishi shart.");
        }
        if (req.password().length() < 6) {
            return err(HttpStatus.BAD_REQUEST, "Parol kamida 6 belgidan iborat bo'lsin.");
        }
        try {
            Client c = auth.register(req.name(), req.email(), req.password());
            return ok(c);
        } catch (AuthService.EmailAlreadyExistsException e) {
            return err(HttpStatus.CONFLICT, "Bu email allaqachon ro'yxatdan o'tgan.");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (isBlank(req.email()) || isBlank(req.password())) {
            return err(HttpStatus.BAD_REQUEST, "Email va parol to'ldirilishi shart.");
        }
        try {
            Client c = auth.login(req.email(), req.password());
            return ok(c);
        } catch (AuthService.InvalidCredentialsException e) {
            return err(HttpStatus.UNAUTHORIZED, "Email yoki parol noto'g'ri.");
        }
    }

    private ResponseEntity<?> ok(Client c) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "token", auth.issueSessionToken(c),
                        "client", Map.of(
                                "id", c.getId().toString(),
                                "name", c.getName(),
                                "email", c.getEmail())));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
