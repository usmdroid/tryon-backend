package uz.tryon.api.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.tryon.api.auth.Client;
import uz.tryon.api.auth.ClientRepository;
import uz.tryon.api.wallet.Wallet;
import uz.tryon.api.wallet.WalletRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Development/staging uchun monitoring demo ma'lumotlari.
 * Faollashtirish: TRYON_DEMO_SEED=true env o'zgaruvchisi.
 * Idempotent: sentinel email mavjud bo'lsa ishlamaydi.
 *
 * Natija: admin va klient monitoring sahifalarida barcha to'rt vaqt
 * diapazoni (hourly/daily/weekly/monthly) uchun grafik ma'lumotlari paydo bo'ladi.
 */
@Component
@ConditionalOnProperty(name = "tryon.demo-seed", havingValue = "true")
public class MonitoringDemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MonitoringDemoSeeder.class);
    private static final String SENTINEL_EMAIL = "demo-seed-alfa@trysima.demo";

    private final ClientRepository clientRepo;
    private final WalletRepository walletRepo;
    private final JdbcTemplate jdbc;

    public MonitoringDemoSeeder(ClientRepository clientRepo,
                                WalletRepository walletRepo,
                                JdbcTemplate jdbc) {
        this.clientRepo = clientRepo;
        this.walletRepo = walletRepo;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (clientRepo.existsByEmail(SENTINEL_EMAIL)) {
            log.info("Demo monitoring ma'lumotlari allaqachon mavjud — o'tkazib yuborildi.");
            return;
        }
        log.info("Demo monitoring ma'lumotlari yaratilmoqda...");

        String[][] clientDefs = {
            {"Demo Alfa Store",       "+998000000001", SENTINEL_EMAIL},
            {"Demo Beta Fashion",     "+998000000002", "demo-seed-beta@trysima.demo"},
            {"Demo Gamma Shop",       "+998000000003", "demo-seed-gamma@trysima.demo"},
            {"Demo Delta Market",     "+998000000004", "demo-seed-delta@trysima.demo"},
            {"Demo Epsilon Boutique", "+998000000005", "demo-seed-epsilon@trysima.demo"},
        };

        List<UUID> clientIds = new ArrayList<>();
        List<UUID> keyIds = new ArrayList<>();

        for (String[] def : clientDefs) {
            Client c = new Client(def[0], def[1], def[2], "demo-nologin");
            clientRepo.save(c);

            Wallet w = new Wallet(c.getId());
            w.setBalanceMsim(500_000L);
            walletRepo.save(w);

            // 2 API kalit (autentifikatsiya uchun emas — faqat monitoring ko'rsatish uchun)
            UUID k1 = UUID.randomUUID();
            UUID k2 = UUID.randomUUID();
            String pfx1 = ("sk_demo_" + c.getId().toString().replace("-", "")).substring(0, 24);
            String pfx2 = ("sk_test_" + c.getId().toString().replace("-", "")).substring(0, 24);
            jdbc.update(
                "INSERT INTO api_keys (id, client_id, name, key_prefix, key_hash) VALUES (?,?,?,?,?)",
                k1, c.getId(), "Demo Key 1", pfx1, "demo-hash-" + k1);
            jdbc.update(
                "INSERT INTO api_keys (id, client_id, name, key_prefix, key_hash) VALUES (?,?,?,?,?)",
                k2, c.getId(), "Demo Key 2", pfx2, "demo-hash-" + k2);

            keyIds.add(k1);
            keyIds.add(k2);
            clientIds.add(c.getId());
        }

        int total = insertTransactions(clientIds, keyIds);
        log.info("Demo monitoring ma'lumotlari tayyor: {} ta mijoz, {} ta tranzaksiya",
            clientIds.size(), total);
    }

    private int insertTransactions(List<UUID> clientIds, List<UUID> keyIds) {
        ZonedDateTime now = Instant.now().atZone(ZoneOffset.UTC);
        Random rng = new Random(42);
        List<Object[]> rows = new ArrayList<>();

        // 12 oy: o'suvchi trend 40 → 200 tx/oy
        for (int mo = 11; mo >= 0; mo--) {
            ZonedDateTime mStart = now.withDayOfMonth(1).minusMonths(mo)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);
            int days = mStart.toLocalDate().lengthOfMonth();
            int count = 40 + (int) ((200 - 40) * (11 - mo) / 11.0) + rng.nextInt(20);

            for (int i = 0; i < count; i++) {
                Instant ts = mStart
                    .plusDays(rng.nextInt(days))
                    .plusHours(rng.nextInt(24))
                    .plusMinutes(rng.nextInt(60))
                    .plusSeconds(rng.nextInt(60))
                    .toInstant();
                if (ts.isAfter(now.toInstant())) {
                    ts = now.minusSeconds(rng.nextInt(60) + 1).toInstant();
                }
                String meta = rng.nextInt(10) == 0 ? "person not detected" : null;
                rows.add(txRow(clientIds, keyIds, rng, ts, meta));
            }
        }

        // Soatlik qoplama: oxirgi 24 soat, har soat 3–8 tx (hourly range uchun)
        for (int h = 0; h < 24; h++) {
            Instant hStart = now.minusHours(h + 1).toInstant();
            int hCount = 3 + rng.nextInt(6);
            for (int i = 0; i < hCount; i++) {
                Instant ts = hStart.plusSeconds(rng.nextInt(3600));
                rows.add(txRow(clientIds, keyIds, rng, ts, null));
            }
        }

        jdbc.batchUpdate(
            "INSERT INTO credit_transactions "
            + "(id, client_id, amount_msim, type, balance_after_msim, meta, api_key_id, created_at) "
            + "VALUES (?,?,?,?,?,?,?,?)",
            rows);
        return rows.size();
    }

    private Object[] txRow(List<UUID> clients, List<UUID> keys, Random rng, Instant ts, String meta) {
        return new Object[]{
            UUID.randomUUID(),
            clients.get(rng.nextInt(clients.size())),
            -1000L,
            "TRYON_DEBIT",
            0L,
            meta,
            keys.get(rng.nextInt(keys.size())),
            Timestamp.from(ts)
        };
    }
}
