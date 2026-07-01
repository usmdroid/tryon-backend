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
import uz.tryon.api.util.BearerExtractor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hamkor o'z statistikasini ko'rish uchun API.
 * Har bir CLIENT faqat o'z ma'lumotlarini oladi (tenant isolation).
 *   GET /api/stats/self              — oxirgi 30 kun umumiy ko'rsatkichlar
 *   GET /api/stats/self/timeseries   — vaqt seriyasi (range=daily|weekly|monthly)
 */
@RestController
@RequestMapping("/api/stats")
public class PartnerStatsController {

    private final AuthService authService;
    private final MonitoringService monitoringService;

    public PartnerStatsController(AuthService authService, MonitoringService monitoringService) {
        this.authService = authService;
        this.monitoringService = monitoringService;
    }

    @GetMapping("/self")
    public ResponseEntity<?> self(HttpServletRequest request) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        MonitoringService.PartnerStats s = monitoringService.partnerStats(clientId);

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("total", s.requests().total());
        req.put("success", s.requests().success());
        req.put("failed", s.requests().failed());

        List<Map<String, Object>> topKeys = s.topKeys().stream().map(k -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("keyId", k.keyId());
            m.put("name", k.name());
            m.put("requests", k.requests());
            return m;
        }).toList();

        List<Map<String, Object>> recentActivity = s.recentActivity().stream().map(a -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", a.ts());
            m.put("type", a.type());
            m.put("status", a.status());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("period", s.period());
        body.put("requests", req);
        body.put("creditsSpentSim", s.creditsSpentSim());
        body.put("balanceSim", s.balanceSim());
        body.put("topKeys", topKeys);
        body.put("recentActivity", recentActivity);

        return ResponseEntity.ok(body);
    }

    @GetMapping("/self/timeseries")
    public ResponseEntity<?> selfTimeseries(
            HttpServletRequest request,
            @RequestParam String range) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID clientId = UUID.fromString(clientIdOpt.get());
        MonitoringService.Timeseries ts;
        try {
            ts = monitoringService.timeseries(clientId, range, null);
        } catch (IllegalArgumentException e) {
            return err(HttpStatus.BAD_REQUEST, "range daily|weekly|monthly dan biri bo'lishi kerak.");
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
}
