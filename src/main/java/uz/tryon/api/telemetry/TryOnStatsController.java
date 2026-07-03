package uz.tryon.api.telemetry;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import uz.tryon.api.auth.AuthService;
import uz.tryon.api.util.BearerExtractor;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Hamkor try-on statistikasi (faqat o'z ma'lumotlari).
 *   GET /api/stats/tryons?from=&to=&bucket=day|source
 *   GET /api/stats/tryons/history?from=&to=&bucket=&key=&limit=50&offset=0
 */
@RestController
@RequestMapping("/api/stats")
public class TryOnStatsController {

    private final AuthService authService;
    private final TryOnEventService eventService;

    public TryOnStatsController(AuthService authService, TryOnEventService eventService) {
        this.authService = authService;
        this.eventService = eventService;
    }

    @GetMapping("/tryons")
    public ResponseEntity<?> tryons(
            HttpServletRequest request,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String bucket) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID partnerId = UUID.fromString(clientIdOpt.get());
        TryOnEventService.StatsResult result;
        try {
            result = eventService.stats(partnerId, Instant.parse(from), Instant.parse(to), bucket);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        List<Map<String, Object>> buckets = result.buckets().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", b.key());
            m.put("label", b.label());
            m.put("count", b.count());
            m.put("success", b.success());
            m.put("fail", b.fail());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bucket", result.bucket());
        body.put("buckets", buckets);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/tryons/history")
    public ResponseEntity<?> history(
            HttpServletRequest request,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String bucket,
            @RequestParam String key,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false, defaultValue = "0") int offset) {
        Optional<String> clientIdOpt = authenticate(request);
        if (clientIdOpt.isEmpty()) return unauthorized();

        UUID partnerId = UUID.fromString(clientIdOpt.get());
        int effectiveLimit = Math.max(1, Math.min(limit, 500));
        int effectiveOffset = Math.max(0, offset);
        TryOnEventService.HistoryResult result;
        try {
            result = eventService.history(partnerId, Instant.parse(from), Instant.parse(to),
                    bucket, key, effectiveLimit, effectiveOffset);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return err(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        List<Map<String, Object>> items = result.items().stream().map(it -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.id());
            m.put("ts", it.ts());
            m.put("platform", it.platform());
            m.put("result", it.result());
            m.put("origin", it.origin());
            m.put("productId", it.productId());
            m.put("clothType", it.clothType());
            m.put("failReason", it.failReason());
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", result.total());
        body.put("limit", result.limit());
        body.put("offset", result.offset());
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
