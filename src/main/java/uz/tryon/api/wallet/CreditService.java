package uz.tryon.api.wallet;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.tryon.api.auth.ApiKeyRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Kredit/hamyon operatsiyalari: bepul grant, to'ldirish (stub), tryon uchun yechish.
 * Balans milli-sim (msim) da saqlanadi: 1 sim = 1000 msim.
 */
@Service
public class CreditService {

    static final long FREE_GRANT_MSIM = 100_000L; // 100 sim

    // Tier chegaralari (umumiy so'rovlar bo'yicha)
    private static final long TIER1_MSIM = 1_000L; // < 1000 req  → 1.00 sim
    private static final long TIER2_MSIM = 950L;   // 1000-9999   → 0.95 sim
    private static final long TIER3_MSIM = 900L;   // >= 10000    → 0.90 sim

    private final WalletRepository wallets;
    private final CreditTransactionRepository txRepo;
    private final ApiKeyRepository apiKeys;

    public CreditService(WalletRepository wallets, CreditTransactionRepository txRepo,
                         ApiKeyRepository apiKeys) {
        this.wallets = wallets;
        this.txRepo = txRepo;
        this.apiKeys = apiKeys;
    }

    public static class InsufficientCreditsException extends RuntimeException { }

    /** Jami so'rovlar soniga qarab narx (msim). */
    public long tierRate(long totalRequests) {
        if (totalRequests < 1_000) return TIER1_MSIM;
        if (totalRequests < 10_000) return TIER2_MSIM;
        return TIER3_MSIM;
    }

    /** Hamyon oladi yoki yaratadi (lock yo'q — faqat o'qish/yaratish uchun). */
    @Transactional
    public Wallet getOrCreateWallet(UUID clientId) {
        return wallets.findById(clientId).orElseGet(() -> wallets.save(new Wallet(clientId)));
    }

    /**
     * Yangi mijozga 100 sim bepul grant — idempotent (qayta chaqirilsa e'tiborsiz).
     * Ro'yxatdan o'tishdan keyin bir marta chaqiriladi.
     */
    @Transactional
    public void grantFree(UUID clientId) {
        if (txRepo.existsByClientIdAndType(clientId, "FREE_GRANT")) return;
        Wallet w = getOrCreateWallet(clientId);
        long newBalance = w.getBalanceMsim() + FREE_GRANT_MSIM;
        w.setBalanceMsim(newBalance);
        wallets.save(w);
        txRepo.save(new CreditTransaction(clientId, FREE_GRANT_MSIM, "FREE_GRANT", newBalance, "Boshlang'ich bepul sim"));
    }

    /**
     * USD miqdorini sim ga aylantiradi va hamyonga qo'shadi. STUB — real to'lov integratsiyasi keyin.
     * 1 USD = 100 sim = 100_000 msim.
     */
    @Transactional
    public Wallet purchase(UUID clientId, double amountUsd) {
        // TODO: real payment gateway integration is a follow-up task.
        long addMsim = Math.round(amountUsd * 100 * 1_000);
        // Pessimistic lock — same pattern as debitForTryOn to prevent lost-update on concurrent purchases.
        Wallet w = wallets.findByClientIdForUpdate(clientId)
                .orElseGet(() -> wallets.save(new Wallet(clientId)));
        long newBalance = w.getBalanceMsim() + addMsim;
        w.setBalanceMsim(newBalance);
        wallets.save(w);
        txRepo.save(new CreditTransaction(clientId, addMsim, "PURCHASE", newBalance,
                String.format("%.2f USD", amountUsd)));
        return wallets.findById(clientId).orElseThrow();
    }

    /**
     * /api/tryon uchun kredit yechadi (pesimistik qulf bilan).
     * Yetarli kredit bo'lmasa — InsufficientCreditsException.
     * Eski (kalitsiz) chaqiruvlar uchun moslik — api_key_id null yoziladi.
     */
    @Transactional
    public void debitForTryOn(UUID clientId) {
        debitForTryOn(clientId, null);
    }

    /**
     * /api/tryon uchun kredit yechadi va so'rovni keltirgan API kalitga bog'laydi.
     * @param apiKeyId TRYON_DEBIT qatoriga yoziladi (nullable — kalit aniqlanmasa null).
     */
    @Transactional
    public void debitForTryOn(UUID clientId, UUID apiKeyId) {
        Wallet w = wallets.findByClientIdForUpdate(clientId)
                .orElseThrow(InsufficientCreditsException::new);

        long rate = tierRate(w.getTotalRequests());
        if (w.getBalanceMsim() < rate) {
            throw new InsufficientCreditsException();
        }

        long newBalance = w.getBalanceMsim() - rate;
        w.setBalanceMsim(newBalance);
        w.setTotalRequests(w.getTotalRequests() + 1);
        wallets.save(w);
        txRepo.save(new CreditTransaction(clientId, -rate, "TRYON_DEBIT", newBalance, null, apiKeyId));

        // Foydalanish aniqlangan kalitga bog'lansa — last_used_at ni hozirgi vaqtga yangilaymiz
        // (null-safe: kalitsiz/legacy chaqiruvlar uchun o'tkazib yuboriladi).
        if (apiKeyId != null) {
            apiKeys.touchLastUsedAt(apiKeyId, Instant.now());
        }
    }

    /** Hamyon holati. */
    @Transactional(readOnly = true)
    public Wallet getWallet(UUID clientId) {
        return getOrCreateWallet(clientId);
    }

    /** Oxirgi N ta tranzaksiya (yangilardan eskiga). */
    @Transactional(readOnly = true)
    public List<CreditTransaction> getTransactions(UUID clientId, int limit) {
        return txRepo.findByClientIdOrderByCreatedAtDesc(clientId, PageRequest.of(0, limit));
    }
}
