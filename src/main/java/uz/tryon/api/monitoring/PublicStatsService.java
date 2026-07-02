package uz.tryon.api.monitoring;

import org.springframework.stereotype.Service;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.wallet.CreditTransactionRepository;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Umumiy statistika (hech qanday autentifikatsiya talab qilinmaydi).
 * Ma'lumotlar 5 daqiqa davomida keshlanadi — har bir so'rovda DB'ga murojaat qilinmaydi.
 *
 * tryOns derivation: credit_transactions jadvalidagi TRYON_DEBIT turining umumiy soni
 * (countAllDebits). Bu faqat muvaffaqiyatli try-on debitlarini o'z ichiga oladi;
 * xatoliklar ham bu yerga tushadi (meta IS NOT NULL), lekin ular ham hisobga olinadi.
 */
@Service
public class PublicStatsService {

    /** Biznes boshlangan oydan buyon o'tgan oylar soni. */
    private static final int OPERATING_MONTHS = 6;

    /** Uptime foizi — hozircha MOCK qiymat. Tashqi monitoring tizimiga ulash rejada. */
    private static final double UPTIME_MOCK = 99.8;

    /** Kesh TTL: 5 daqiqa. */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    /** Haqiqiy ma'lumot bo'lmasa (partners == 0) ishlatiluvchi minimal kafolatlangan qiymat. */
    private static final StatsSnapshot FLOOR = new StatsSnapshot(3, 100L, OPERATING_MONTHS, 99.5);

    private final ClientRepository clients;
    private final CreditTransactionRepository transactions;

    private final AtomicReference<CachedStats> cache = new AtomicReference<>();

    public PublicStatsService(ClientRepository clients, CreditTransactionRepository transactions) {
        this.clients = clients;
        this.transactions = transactions;
    }

    public record StatsSnapshot(int partners, long tryOns, int months, double uptime) {}

    private record CachedStats(StatsSnapshot data, long fetchedAt) {
        boolean valid() {
            return System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS;
        }
    }

    public StatsSnapshot stats() {
        CachedStats cached = cache.get();
        if (cached != null && cached.valid()) {
            return cached.data();
        }
        StatsSnapshot fresh = load();
        cache.set(new CachedStats(fresh, System.currentTimeMillis()));
        return fresh;
    }

    private StatsSnapshot load() {
        long partners = clients.countByStatus("ACTIVE");
        if (partners == 0) {
            return FLOOR;
        }
        long tryOns = transactions.countAllDebits();
        return new StatsSnapshot((int) partners, tryOns, OPERATING_MONTHS, UPTIME_MOCK);
    }
}
