package uz.tryon.api.telemetry;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Try-on voqealarini yozish va statistika olish. */
@Service
public class TryOnEventService {

    private final TryOnEventRepository repo;

    public TryOnEventService(TryOnEventRepository repo) {
        this.repo = repo;
    }

    public record BucketEntry(String key, String label, long count, long success, long fail) {}
    public record StatsResult(String bucket, List<BucketEntry> buckets) {}
    public record EventItem(String id, Instant ts, String platform, String result,
                            String origin, String productId, String productName,
                            String clothType, String failReason,
                            String deviceIdShort, String ipMasked) {}
    public record HistoryResult(List<EventItem> items, long total, int limit, int offset) {}
    public record TopProductEntry(String productId, String productName, long count) {}

    @Transactional
    public void record(String platform, String origin, UUID partnerId, String deviceId,
                       String productId, String productName, String clothType,
                       String result, String failReason, long durationMs, String clientIp,
                       boolean emulator) {
        repo.save(new TryOnEvent(platform, origin, partnerId, deviceId,
                productId, productName, clothType, result, failReason, durationMs, clientIp,
                emulator));
    }

    @Transactional(readOnly = true)
    public StatsResult stats(UUID partnerId, Instant from, Instant to, String bucket) {
        List<TryOnEventRepository.BucketRow> rows = switch (bucket) {
            case "day" -> repo.aggregateByDay(partnerId, from, to);
            case "source" -> repo.aggregateBySource(partnerId, from, to);
            default -> throw new IllegalArgumentException("bucket day|source bo'lishi kerak");
        };
        List<BucketEntry> entries = rows.stream()
                .map(r -> new BucketEntry(r.getKey(), labelFor(bucket, r.getKey()),
                        r.getCount(), r.getSuccess(), r.getFail()))
                .toList();
        return new StatsResult(bucket, entries);
    }

    @Transactional(readOnly = true)
    public HistoryResult history(UUID partnerId, Instant from, Instant to,
                                 String bucket, String key, int limit, int offset) {
        List<TryOnEventRepository.EventRow> rows;
        long total;
        if ("day".equals(bucket)) {
            rows = repo.historyByDay(partnerId, from, to, key, limit, offset);
            total = repo.countHistoryByDay(partnerId, from, to, key);
        } else if ("source".equals(bucket)) {
            rows = repo.historyBySource(partnerId, from, to, key, limit, offset);
            total = repo.countHistoryBySource(partnerId, from, to, key);
        } else {
            throw new IllegalArgumentException("bucket day|source bo'lishi kerak");
        }
        List<EventItem> items = rows.stream()
                .map(r -> new EventItem(r.getId(), r.getTs(), r.getPlatform(), r.getResult(),
                        r.getOrigin(), r.getProductId(), r.getProductName(), r.getClothType(),
                        r.getFailReason(), shortDeviceId(r.getDeviceId()), maskIp(r.getClientIp())))
                .toList();
        return new HistoryResult(items, total, limit, offset);
    }

    @Transactional(readOnly = true)
    public List<TopProductEntry> topProducts(UUID partnerId, Instant from, Instant to, int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        return repo.topProducts(partnerId, from, to, effectiveLimit).stream()
                .map(r -> new TopProductEntry(r.getProductId(), r.getProductName(), r.getCount()))
                .toList();
    }

    static String shortDeviceId(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        return deviceId.length() <= 6 ? deviceId : "…" + deviceId.substring(deviceId.length() - 6);
    }

    static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        int last = ip.lastIndexOf('.');
        if (last > 0) {
            int prev = ip.lastIndexOf('.', last - 1);
            if (prev > 0) return ip.substring(0, prev) + ".*.*"; // IPv4
        }
        // IPv6: keep first half
        int mid = ip.length() / 2;
        int colon = ip.indexOf(':', mid);
        return colon > 0 ? ip.substring(0, colon) + ":****" : ip;
    }

    private String labelFor(String bucket, String key) {
        if ("source".equals(bucket)) {
            return switch (key) {
                case "partner_site" -> "Hamkor sayt";
                case "marketplace" -> "Bozor";
                case "dev_sandbox" -> "Ishlab chiqish";
                default -> key;
            };
        }
        return key;
    }
}
