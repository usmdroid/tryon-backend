package uz.tryon.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tryon.api.wallet.CreditTransaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin moderation endpoints (/api/admin/moderation/*).
 * Gated by requireStaff — CLIENT role → 403.
 */
@RestController
@RequestMapping("/api/admin/moderation")
public class ModerationController {

    private static final int DEFAULT_LIMIT = 50;

    private final AdminAccessService access;
    private final ModerationService moderationService;

    public ModerationController(AdminAccessService access, ModerationService moderationService) {
        this.access = access;
        this.moderationService = moderationService;
    }

    /** Set moderation_status = HIDDEN. Returns 404 if transaction not found. */
    @PostMapping("/{id}/hide")
    public ResponseEntity<?> hide(@PathVariable UUID id, HttpServletRequest request) {
        access.requireStaff(request);
        CreditTransaction tx = moderationService.setStatus(id, "HIDDEN");
        return ResponseEntity.ok(statusResponse(tx));
    }

    /** Set moderation_status = FLAGGED. Returns 404 if transaction not found. */
    @PostMapping("/{id}/flag")
    public ResponseEntity<?> flag(@PathVariable UUID id, HttpServletRequest request) {
        access.requireStaff(request);
        CreditTransaction tx = moderationService.setStatus(id, "FLAGGED");
        return ResponseEntity.ok(statusResponse(tx));
    }

    /** Set moderation_status = VISIBLE. Returns 404 if transaction not found. */
    @PostMapping("/{id}/restore")
    public ResponseEntity<?> restore(@PathVariable UUID id, HttpServletRequest request) {
        access.requireStaff(request);
        CreditTransaction tx = moderationService.setStatus(id, "VISIBLE");
        return ResponseEntity.ok(statusResponse(tx));
    }

    /**
     * Paginated moderation list.
     * filter: all (default) | flagged | hidden
     * limit/offset: pagination (default 50/0).
     */
    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(name = "filter", defaultValue = "all") String filter,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset,
            HttpServletRequest request) {
        access.requireStaff(request);

        if (limit <= 0) limit = DEFAULT_LIMIT;
        if (offset < 0) offset = 0;

        List<Object[]> rows = moderationService.list(filter, limit, offset);
        long total = moderationService.count(filter);

        List<Map<String, Object>> items = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r[0]);
            m.put("clientId", r[1]);
            m.put("clientName", r[2]);
            m.put("type", r[3]);
            long amountMsim = r[4] instanceof Number n ? n.longValue() : 0L;
            m.put("amountSim", amountMsim / 1000.0);
            m.put("moderationStatus", r[5]);
            m.put("meta", r[6]);
            m.put("createdAt", r[7]);
            return m;
        }).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", total);
        body.put("limit", limit);
        body.put("offset", offset);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> statusResponse(CreditTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("moderationStatus", tx.getModerationStatus());
        return m;
    }
}
