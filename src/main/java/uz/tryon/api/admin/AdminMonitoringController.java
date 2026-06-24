package uz.tryon.api.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.tryon.api.wallet.CreditTransactionRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Super-admin global monitoring endpoint.
 *   GET /api/admin/monitoring/global?range=daily|weekly|monthly  (default: daily)
 * Auth: AdminAccessService.requireSuperAdmin() — 401 token yo'q/yaroqsiz, 403 super-admin emas.
 *
 * Muvaffaqiyatsiz try-on ta'rifi: TRYON_DEBIT qatori bilan null emas va bo'sh bo'lmagan meta.
 * Sabab: history() da ham xuddi shu logika qo'llaniladi — meta bo'sh/null => "success".
 */
@RestController
@RequestMapping("/api/admin/monitoring")
public class AdminMonitoringController {

    private final AdminAccessService access;
    private final CreditTransactionRepository txRepo;

    public AdminMonitoringController(AdminAccessService access, CreditTransactionRepository txRepo) {
        this.access = access;
        this.txRepo = txRepo;
    }

    @GetMapping("/global")
    public ResponseEntity<?> globalMonitoring(
            HttpServletRequest request,
            @RequestParam(defaultValue = "daily") String range) {

        access.requireSuperAdmin(request);

        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        Instant dayAgo   = now.minusDays(1).toInstant();
        Instant weekAgo  = now.minusWeeks(1).toInstant();
        Instant monthAgo = now.minusDays(30).toInstant();

        // totalRequests: kun, hafta, oy ichidagi so'rovlar soni
        Map<String, Long> totalRequests = new LinkedHashMap<>();
        totalRequests.put("day",   txRepo.countGlobalDebitsSince(dayAgo));
        totalRequests.put("week",  txRepo.countGlobalDebitsSince(weekAgo));
        totalRequests.put("month", txRepo.countGlobalDebitsSince(monthAgo));

        // topClients: top 10 mijoz, TRYON_DEBIT soni bo'yicha
        List<Map<String, Object>> topClients = txRepo.globalTopClients(10).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientId", r.getClientId());
            m.put("name", r.getName());
            m.put("requests", r.getRequests());
            m.put("spentSim", r.getSpentMsim() / 1000.0);
            return m;
        }).toList();

        // creditSpendTrend: range bo'yicha global vaqt seriyasi
        String bucket;
        Instant since;
        String resolvedRange;
        switch (range) {
            case "weekly" -> {
                bucket        = "week";
                since         = now.truncatedTo(ChronoUnit.DAYS).minusWeeks(11).toInstant();
                resolvedRange = "weekly";
            }
            case "monthly" -> {
                bucket        = "month";
                since         = now.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).minusMonths(11).toInstant();
                resolvedRange = "monthly";
            }
            default -> {
                bucket        = "day";
                since         = now.truncatedTo(ChronoUnit.DAYS).minusDays(29).toInstant();
                resolvedRange = "daily";
            }
        }

        List<Map<String, Object>> buckets = txRepo.globalTimeseries(bucket, since).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", r.getTs());
            m.put("spentSim", r.getSpentMsim() / 1000.0);
            return m;
        }).toList();

        Map<String, Object> creditSpendTrend = new LinkedHashMap<>();
        creditSpendTrend.put("range", resolvedRange);
        creditSpendTrend.put("buckets", buckets);

        // errorRate: oxirgi 30 kun (monthAgo), xato = non-blank meta
        long totalTryons = txRepo.countGlobalDebitsSince(monthAgo);
        long failed      = txRepo.countGlobalFailedSince(monthAgo);
        double rate      = totalTryons == 0 ? 0.0 : (double) failed / totalTryons;

        Map<String, Object> errorRate = new LinkedHashMap<>();
        errorRate.put("totalTryons", totalTryons);
        errorRate.put("failed", failed);
        errorRate.put("rate", rate);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalRequests", totalRequests);
        body.put("topClients", topClients);
        body.put("creditSpendTrend", creditSpendTrend);
        body.put("errorRate", errorRate);

        return ResponseEntity.ok(body);
    }
}
