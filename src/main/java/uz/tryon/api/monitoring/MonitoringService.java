package uz.tryon.api.monitoring;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tryon.api.auth.ApiKey;
import uz.tryon.api.auth.ApiKeyRepository;
import uz.tryon.api.wallet.CreditTransactionRepository;
import uz.tryon.api.wallet.Wallet;
import uz.tryon.api.wallet.WalletRepository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kabinet "Monitoring" sahifasi uchun agregatlar.
 * Barcha hisob-kitoblar DB tarafida (COUNT/SUM/date_trunc) — qatorlarni xotiraga yuklamaymiz.
 * Balans msim da: 1 sim = 1000 msim.
 */
@Service
public class MonitoringService {

    private final CreditTransactionRepository txRepo;
    private final WalletRepository wallets;
    private final ApiKeyRepository apiKeys;

    public MonitoringService(CreditTransactionRepository txRepo,
                             WalletRepository wallets,
                             ApiKeyRepository apiKeys) {
        this.txRepo = txRepo;
        this.wallets = wallets;
        this.apiKeys = apiKeys;
    }

    public record Summary(long totalRequests, double totalSpentSim, double balanceSim, int keysCount) { }

    public record KeyUsage(UUID apiKeyId, String name, String keyPrefix,
                           long requests, double spentSim, Instant lastUsedAt, Instant revokedAt) { }

    public record Bucket(Instant ts, long count, double spentSim) { }

    public record Timeseries(String range, List<Bucket> buckets) { }

    @Transactional(readOnly = true)
    public Summary summary(UUID clientId) {
        // totalRequests: TRYON_DEBIT qatorlari soni (by-key bilan mos kelishi uchun
        // wallet.total_requests o'rniga debit qatorlari sanaladi).
        long totalRequests = txRepo.countDebits(clientId);
        double totalSpentSim = txRepo.sumDebitMsim(clientId) / 1000.0;
        double balanceSim = wallets.findById(clientId).map(Wallet::getBalanceMsim).orElse(0L) / 1000.0;
        int keysCount = (int) apiKeys.countByClientIdAndRevokedAtIsNull(clientId);
        return new Summary(totalRequests, totalSpentSim, balanceSim, keysCount);
    }

    @Transactional(readOnly = true)
    public List<KeyUsage> byKey(UUID clientId) {
        // Kalit id -> agregat (faqat foydalanish bor kalitlar va null guruh shu yerda bo'ladi).
        Map<UUID, CreditTransactionRepository.KeyUsageRow> agg = new HashMap<>();
        boolean hasUnattributed = false;
        CreditTransactionRepository.KeyUsageRow nullRow = null;
        for (var row : txRepo.aggregateDebitByKey(clientId)) {
            if (row.getApiKeyId() == null) {
                hasUnattributed = true;
                nullRow = row;
            } else {
                agg.put(row.getApiKeyId(), row);
            }
        }

        List<KeyUsage> result = new ArrayList<>();
        // Mijozning barcha kalitlari (0-li bo'lsa ham), createdAt desc tartibida.
        for (ApiKey k : apiKeys.findByClientIdOrderByCreatedAtDesc(clientId)) {
            var row = agg.get(k.getId());
            long requests = row == null ? 0L : row.getRequests();
            double spentSim = (row == null ? 0L : row.getSpentMsim()) / 1000.0;
            result.add(new KeyUsage(k.getId(), k.getName(), k.getKeyPrefix(),
                    requests, spentSim, k.getLastUsedAt(), k.getRevokedAt()));
        }

        // Atributsiyasiz (legacy/sessiya) foydalanish bo'lsa — bitta qo'shimcha qator.
        if (hasUnattributed) {
            result.add(new KeyUsage(null, "Noma'lum", null,
                    nullRow.getRequests(), nullRow.getSpentMsim() / 1000.0, null, null));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Timeseries timeseries(UUID clientId, String range, UUID apiKeyId) {
        Window w = windowFor(range);
        String apiKeyParam = apiKeyId == null ? null : apiKeyId.toString();
        List<Bucket> buckets = txRepo.aggregateTimeseries(clientId, w.bucket(), w.since(), apiKeyParam).stream()
                .map(r -> new Bucket(r.getTs(), r.getCnt(), r.getSpentMsim() / 1000.0))
                .toList();
        return new Timeseries(range, buckets);
    }

    /**
     * Range → (date_trunc buketi, "since" chegarasi). "since" buket boshiga tekislanadi
     * (truncatedTo), shunda buketlar soni aniq bo'ladi: monthly → 12 ta oy, daily → 30 kun, h.k.
     * Noma'lum range → IllegalArgumentException (controller 400 qaytaradi).
     */
    private static Window windowFor(String range) {
        if (range == null) throw new IllegalArgumentException("range majburiy");
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        return switch (range) {
            // oxirgi 24 soat, soatlik — joriy soat boshidan 23 soat oldin (24 ta buket)
            case "hourly"  -> new Window("hour",
                    now.truncatedTo(ChronoUnit.HOURS).minusHours(23).toInstant());
            // oxirgi 30 kun, kunlik — joriy kun boshidan 29 kun oldin (30 ta buket)
            case "daily"   -> new Window("day",
                    now.truncatedTo(ChronoUnit.DAYS).minusDays(29).toInstant());
            // oxirgi 12 hafta, haftalik — joriy kun boshidan 11 hafta oldin (12 ta buket)
            case "weekly"  -> new Window("week",
                    now.truncatedTo(ChronoUnit.DAYS).minusWeeks(11).toInstant());
            // oxirgi 12 oy, oylik — joriy oy boshidan 11 oy oldin (aniq 12 ta buket)
            case "monthly" -> new Window("month",
                    now.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1).minusMonths(11).toInstant());
            default -> throw new IllegalArgumentException("noma'lum range: " + range);
        };
    }

    private record Window(String bucket, Instant since) { }
}
