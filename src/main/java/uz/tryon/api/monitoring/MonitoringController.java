package uz.tryon.api.monitoring;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tryon.api.auth.AuthService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Kabinet "Monitoring" sahifasi API'si.
 *   GET /api/monitoring/summary
 *   GET /api/monitoring/by-key
 *   GET /api/monitoring/timeseries?range=hourly|daily|weekly|monthly&apiKeyId=<ixtiyoriy>
 * Auth: Authorization: Bearer <session-token> — joriy mijozga scoped (WalletController bilan bir xil).
 */
@RestController
@RequestMapping("/api/monitoring")
public class MonitoringController {

    private final AuthService authService;
    private final MonitoringService monitoringService;

    public MonitoringController(AuthService authService, MonitoringService monitoringService) {
        this.authService = authService;
        this.monitoringService = monitoringService;
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(HttpServletRequest request) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        MonitoringService.Summary s = monitoringService.summary(clientId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRequests", s.totalRequests());
        m.put("totalSpentSim", s.totalSpentSim());
        m.put("balanceSim", s.balanceSim());
        m.put("keysCount", s.keysCount());
        return ResponseEntity.ok(m);
    }

    @GetMapping("/by-key")
    public ResponseEntity<?> byKey(HttpServletRequest request) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        List<Map<String, Object>> result = monitoringService.byKey(clientId).stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("apiKeyId", k.apiKeyId());
            m.put("name", k.name());
            m.put("keyPrefix", k.keyPrefix());
            m.put("requests", k.requests());
            m.put("spentSim", k.spentSim());
            m.put("lastUsedAt", k.lastUsedAt());
            m.put("revokedAt", k.revokedAt());
            return m;
        }).toList();
        return ResponseEntity.ok(result);
    }

    @GetMapping("/timeseries")
    public ResponseEntity<?> timeseries(
            HttpServletRequest request,
            @RequestParam String range,
            @RequestParam(required = false) UUID apiKeyId) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        MonitoringService.Timeseries ts;
        try {
            ts = monitoringService.timeseries(clientId, range, apiKeyId);
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST,
                    "range hourly|daily|weekly|monthly dan biri bo'lishi kerak.");
        }

        List<Map<String, Object>> buckets = ts.buckets().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", b.ts());
            m.put("count", b.count());
            m.put("spentSim", b.spentSim());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("range", ts.range());
        body.put("buckets", buckets);
        return ResponseEntity.ok(body);
    }

    private Optional<String> authenticate(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        return authService.verifySessionToken(header.substring(7));
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
