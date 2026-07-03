package uz.tryon.api.devsandbox;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.auth.AuthService;
import uz.tryon.api.util.BearerExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Authenticated developer sandbox key endpoints (dashboard session token required).
 *   POST /api/dev-keys  — generate a new dev_ key for the current client
 *   GET  /api/dev-keys  — list current client's dev keys
 */
@RestController
@RequestMapping("/api/dev-keys")
public class DevKeyController {

    private final AuthService authService;
    private final DevSandboxKeyService devKeyService;

    public DevKeyController(AuthService authService, DevSandboxKeyService devKeyService) {
        this.authService = authService;
        this.devKeyService = devKeyService;
    }

    @PostMapping
    public ResponseEntity<?> generate(HttpServletRequest request) {
        Optional<UUID> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        DevSandboxKey key = devKeyService.generate(clientIdOpt.get());
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "devKey", key.getDevKey(),
                        "used", key.getUsedCount(),
                        "max", key.getMaxCount()
                ));
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        Optional<UUID> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        List<Map<String, Object>> result = devKeyService.listForClient(clientIdOpt.get())
                .stream().map(k -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", k.getId());
                    m.put("devKey", k.getDevKey());
                    m.put("used", k.getUsedCount());
                    m.put("max", k.getMaxCount());
                    m.put("createdAt", k.getCreatedAt());
                    m.put("lastUsedAt", k.getLastUsedAt());
                    m.put("revoked", k.getRevokedAt() != null);
                    return m;
                }).toList();
        return ResponseEntity.ok(result);
    }

    private Optional<UUID> authenticate(HttpServletRequest req) {
        return BearerExtractor.extract(req)
                .flatMap(authService::verifySessionToken)
                .map(id -> {
                    try { return UUID.fromString(id); } catch (IllegalArgumentException e) { return null; }
                })
                .filter(id -> id != null);
    }

    private ResponseEntity<Map<String, String>> unauthorized() {
        return err(HttpStatus.UNAUTHORIZED, "Sessiya tokeni xato yoki muddati o'tgan.");
    }

    private ResponseEntity<Map<String, String>> err(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message));
    }
}
