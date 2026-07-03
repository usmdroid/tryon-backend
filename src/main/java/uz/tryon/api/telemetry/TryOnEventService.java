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
                            String origin, String productId, String clothType, String failReason) {}
    public record HistoryResult(List<EventItem> items, long total, int limit, int offset) {}

    @Transactional
    public void record(String platform, String origin, UUID partnerId, String deviceId,
                       String productId, String clothType, String result, String failReason,
                       long durationMs) {
        repo.save(new TryOnEvent(platform, origin, partnerId, deviceId,
                productId, clothType, result, failReason, durationMs));
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
                        r.getOrigin(), r.getProductId(), r.getClothType(), r.getFailReason()))
                .toList();
        return new HistoryResult(items, total, limit, offset);
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
