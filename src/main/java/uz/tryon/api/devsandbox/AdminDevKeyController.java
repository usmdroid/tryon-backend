package uz.tryon.api.devsandbox;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.admin.AdminAccessService;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.util.ApiErrors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Super-admin dev sandbox key panel.
 * MODERATOR receives 403 — requireSuperAdmin() enforces this.
 *
 * GET  /api/admin/dev-keys       — list all keys + totals
 * POST /api/admin/dev-keys/{id}/revoke — revoke a key
 */
@RestController
@RequestMapping("/api/admin/dev-keys")
public class AdminDevKeyController {

    /** Estimated GPU cost per dev try-on (USD). Used for estGpuCost display only. */
    private static final double COST_PER_TRYON = 0.02;

    private final AdminAccessService access;
    private final DevSandboxKeyService devKeyService;
    private final ClientRepository clients;

    public AdminDevKeyController(AdminAccessService access,
                                  DevSandboxKeyService devKeyService,
                                  ClientRepository clients) {
        this.access = access;
        this.devKeyService = devKeyService;
        this.clients = clients;
    }

    @GetMapping
    public ResponseEntity<?> listKeys(HttpServletRequest request) {
        access.requireSuperAdmin(request);

        List<DevSandboxKey> keys = devKeyService.listAll();

        // Pre-fetch client names for created_by UUIDs to avoid N+1
        Map<UUID, Client> clientMap = new java.util.HashMap<>();
        for (DevSandboxKey k : keys) {
            if (k.getCreatedBy() != null && !clientMap.containsKey(k.getCreatedBy())) {
                clients.findById(k.getCreatedBy()).ifPresent(c -> clientMap.put(c.getId(), c));
            }
        }

        long totalDevTryOns = 0;
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (DevSandboxKey k : keys) {
            totalDevTryOns += k.getUsedCount();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("devKeyMasked", maskKey(k.getDevKey()));
            m.put("createdBy", createdByLabel(k.getCreatedBy(), clientMap));
            m.put("used", k.getUsedCount());
            m.put("max", k.getMaxCount());
            m.put("createdAt", k.getCreatedAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            m.put("revoked", k.getRevokedAt() != null);
            m.put("estGpuCost", Math.round(k.getUsedCount() * COST_PER_TRYON * 100.0) / 100.0);
            rows.add(m);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalKeys", keys.size());
        summary.put("totalDevTryOns", totalDevTryOns);
        summary.put("totalEstCost", Math.round(totalDevTryOns * COST_PER_TRYON * 100.0) / 100.0);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keys", rows);
        body.put("summary", summary);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{id}/revoke")
    public ResponseEntity<?> revokeKey(@PathVariable UUID id, HttpServletRequest request) {
        access.requireSuperAdmin(request);

        Optional<DevSandboxKey> opt = devKeyService.revoke(id);
        if (opt.isEmpty()) return ApiErrors.err(HttpStatus.NOT_FOUND, "Kalit topilmadi.");
        return ResponseEntity.ok(Map.of("ok", true, "revokedAt", opt.get().getRevokedAt()));
    }

    /** Shows tag prefix and last 4 chars only: dev_…wxyz */
    private static String maskKey(String devKey) {
        if (devKey == null || devKey.length() <= 8) return devKey;
        return "dev_…" + devKey.substring(devKey.length() - 4);
    }

    private static String createdByLabel(UUID createdBy, Map<UUID, Client> clientMap) {
        if (createdBy == null) return "—";
        Client c = clientMap.get(createdBy);
        if (c == null) return createdBy.toString();
        return c.getName() + " (" + c.getPhone() + ")";
    }
}
