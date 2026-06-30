package uz.tryon.api.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import uz.tryon.api.util.BearerExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * API kalit boshqaruvi (dashboard uchun).
 *   GET    /api/api-keys        — ro'yxat
 *   POST   /api/api-keys        { name } — yaratish
 *   DELETE /api/api-keys/{id}   — bekor qilish
 * Auth: Authorization: Bearer <session-token>
 */
@RestController
@RequestMapping("/api/api-keys")
public class ApiKeyController {

    private final AuthService authService;
    private final ApiKeyService apiKeyService;

    public ApiKeyController(AuthService authService, ApiKeyService apiKeyService) {
        this.authService = authService;
        this.apiKeyService = apiKeyService;
    }

    public record CreateRequest(String name) { }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        List<Map<String, Object>> result = apiKeyService.listByClient(clientId).stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("name", k.getName());
            m.put("keyPrefix", k.getKeyPrefix());
            m.put("createdAt", k.getCreatedAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            m.put("revokedAt", k.getRevokedAt());
            m.put("revealable", k.getRevokedAt() == null && k.getKeyEnc() != null);
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request, @RequestBody CreateRequest req) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();
        if (isBlank(req.name())) return err(HttpStatus.BAD_REQUEST, "Nom to'ldirilishi shart.");
        if (req.name().trim().length() > 255) return err(HttpStatus.BAD_REQUEST, "Nom 255 belgidan oshmasligi kerak.");

        UUID clientId = UUID.fromString(clientIdOpt.get());
        ApiKeyService.CreateResult result = apiKeyService.create(clientId, req.name().trim());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.key().getId());
        body.put("name", result.key().getName());
        body.put("key", result.secret());
        body.put("keyPrefix", result.key().getKeyPrefix());
        body.put("createdAt", result.key().getCreatedAt());
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> revoke(@PathVariable UUID id, HttpServletRequest request) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        try {
            apiKeyService.revoke(id, clientId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (ApiKeyService.NotFoundException e) {
            return err(HttpStatus.NOT_FOUND, "API kalit topilmadi.");
        }
    }

    private Optional<String> authenticate(HttpServletRequest req) {
        return BearerExtractor.extract(req).flatMap(authService::verifySessionToken);
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return err(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
