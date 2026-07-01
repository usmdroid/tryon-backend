package uz.tryon.api.wallet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    List<CreditTransaction> findByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    boolean existsByClientIdAndType(UUID clientId, String type);

    // ---- Monitoring aggregatlari (DB tarafida — qatorlarni xotiraga yuklamaymiz) ----

    /** Mijozning TRYON_DEBIT qatorlari soni. */
    @Query("SELECT COUNT(t) FROM CreditTransaction t WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT'")
    long countDebits(@Param("clientId") UUID clientId);

    /** Mijozning TRYON_DEBIT bo'yicha jami sarfi (msim, musbat). amount_msim manfiy → ABS. */
    @Query("SELECT COALESCE(SUM(ABS(t.amountMsim)), 0) FROM CreditTransaction t "
            + "WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT'")
    long sumDebitMsim(@Param("clientId") UUID clientId);

    // ---- Partner self-stats: vaqt chegarasi bilan (oxirgi 30 kun) ----

    /** Mijozning TRYON_DEBIT qatorlari soni bir vaqt chegarasidan beri. */
    @Query("SELECT COUNT(t) FROM CreditTransaction t WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT' AND t.createdAt >= :since")
    long countDebitsSince(@Param("clientId") UUID clientId, @Param("since") Instant since);

    /** Mijozning muvaffaqiyatsiz TRYON_DEBIT soni (meta bo'sh emas = xato). */
    @Query("SELECT COUNT(t) FROM CreditTransaction t WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT' AND t.createdAt >= :since AND t.meta IS NOT NULL AND LENGTH(t.meta) > 0")
    long countFailedDebitsSince(@Param("clientId") UUID clientId, @Param("since") Instant since);

    /** Mijozning TRYON_DEBIT bo'yicha jami sarfi (msim) bir vaqt chegarasidan beri. */
    @Query("SELECT COALESCE(SUM(ABS(t.amountMsim)), 0) FROM CreditTransaction t WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT' AND t.createdAt >= :since")
    long sumDebitMsimSince(@Param("clientId") UUID clientId, @Param("since") Instant since);

    /** API kalit bo'yicha TRYON_DEBIT agregati bir vaqt chegarasidan beri (eng ko'p so'rovli kalitlar). */
    @Query("SELECT t.apiKeyId AS apiKeyId, COUNT(t) AS requests, COALESCE(SUM(ABS(t.amountMsim)), 0) AS spentMsim "
            + "FROM CreditTransaction t "
            + "WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT' AND t.createdAt >= :since AND t.apiKeyId IS NOT NULL "
            + "GROUP BY t.apiKeyId "
            + "ORDER BY COUNT(t) DESC")
    List<KeyUsageRow> aggregateDebitByKeySince(@Param("clientId") UUID clientId, @Param("since") Instant since);

    /** Mijozning oxirgi N ta TRYON_DEBIT qatorlari (sahifalash uchun Pageable). */
    @Query("SELECT t FROM CreditTransaction t WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT' ORDER BY t.createdAt DESC")
    List<CreditTransaction> findRecentTryonDebits(@Param("clientId") UUID clientId, org.springframework.data.domain.Pageable pageable);

    // ---- Admin (super-admin) agregatlari — barcha mijozlar bo'yicha ----

    /** Barcha mijozlar bo'yicha TRYON_DEBIT qatorlari soni (admin statistikasi). */
    @Query("SELECT COUNT(t) FROM CreditTransaction t WHERE t.type = 'TRYON_DEBIT'")
    long countAllDebits();

    /** Barcha mijozlar bo'yicha PURCHASE summasi (msim) — tushum hisoboti uchun. */
    @Query("SELECT COALESCE(SUM(t.amountMsim), 0) FROM CreditTransaction t WHERE t.type = 'PURCHASE'")
    long sumAllPurchaseMsim();

    /**
     * Kalit bo'yicha TRYON_DEBIT agregati (faqat foydalanish bor kalitlar/null guruh).
     * Kalitlar ro'yxati bilan birikma servisda amalga oshiriladi (0-li kalitlar ham ko'rinishi uchun).
     */
    @Query("SELECT t.apiKeyId AS apiKeyId, COUNT(t) AS requests, COALESCE(SUM(ABS(t.amountMsim)), 0) AS spentMsim "
            + "FROM CreditTransaction t "
            + "WHERE t.clientId = :clientId AND t.type = 'TRYON_DEBIT' "
            + "GROUP BY t.apiKeyId")
    List<KeyUsageRow> aggregateDebitByKey(@Param("clientId") UUID clientId);

    /**
     * Vaqt-qator agregati: TRYON_DEBIT qatorlarini date_trunc(bucket) bo'yicha guruhlaydi.
     * bucket — 'hour' | 'day' | 'week' | 'month' (faqat servis tomonidan validatsiyalangan qiymatlar).
     * apiKeyId null bo'lsa — barcha kalitlar; aks holda shu kalitga filtr.
     * Faqat ma'lumotli buketlar qaytadi (frontend bo'shliqlarni to'ldiradi).
     */
    @Query(value = "SELECT date_trunc(:bucket, created_at) AS ts, "
            + "COUNT(*) AS cnt, COALESCE(SUM(ABS(amount_msim)), 0) AS spent_msim "
            + "FROM credit_transactions "
            + "WHERE client_id = :clientId AND type = 'TRYON_DEBIT' "
            + "AND created_at >= :since "
            + "AND (CAST(:apiKeyId AS uuid) IS NULL OR api_key_id = CAST(:apiKeyId AS uuid)) "
            + "GROUP BY 1 "
            + "ORDER BY 1 ASC", nativeQuery = true)
    List<TimeBucketRow> aggregateTimeseries(@Param("clientId") UUID clientId,
                                            @Param("bucket") String bucket,
                                            @Param("since") Instant since,
                                            @Param("apiKeyId") String apiKeyId);

    /**
     * Tarix (history): mijozning TRYON_DEBIT qatorlari, har biri alohida (agregat emas).
     * api_keys bilan LEFT JOIN — kalit nomi/prefiksi (o'chirilgan/null bo'lsa null).
     * apiKeyId null bo'lsa — barcha kalitlar; aks holda shu kalitga filtr.
     * created_at bo'yicha kamayuvchi tartib, LIMIT/OFFSET bilan sahifalash.
     */
    @Query(value = "SELECT t.id AS id, "
            + "t.created_at AS created_at, "
            + "t.api_key_id AS api_key_id, "
            + "k.name AS key_name, "
            + "k.key_prefix AS key_prefix, "
            + "t.meta AS meta, "
            + "ABS(t.amount_msim) AS spent_msim "
            + "FROM credit_transactions t "
            + "LEFT JOIN api_keys k ON k.id = t.api_key_id "
            + "WHERE t.client_id = :clientId AND t.type = 'TRYON_DEBIT' "
            + "AND (CAST(:apiKeyId AS uuid) IS NULL OR t.api_key_id = CAST(:apiKeyId AS uuid)) "
            + "ORDER BY t.created_at DESC "
            + "LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<HistoryRow> history(@Param("clientId") UUID clientId,
                             @Param("apiKeyId") String apiKeyId,
                             @Param("limit") int limit,
                             @Param("offset") int offset);

    /** history bilan bir xil filtr bo'yicha jami qatorlar soni (sahifalash uchun). */
    @Query(value = "SELECT COUNT(*) FROM credit_transactions t "
            + "WHERE t.client_id = :clientId AND t.type = 'TRYON_DEBIT' "
            + "AND (CAST(:apiKeyId AS uuid) IS NULL OR t.api_key_id = CAST(:apiKeyId AS uuid))",
            nativeQuery = true)
    long countHistory(@Param("clientId") UUID clientId,
                      @Param("apiKeyId") String apiKeyId);

    // ---- Global Admin Monitoring agregatlari (barcha mijozlar, clientId filtrisiz) ----

    /** Barcha TRYON_DEBIT qatorlari soni bir vaqt chegarasidan beri (global). */
    @Query("SELECT COUNT(t) FROM CreditTransaction t WHERE t.type = 'TRYON_DEBIT' AND t.createdAt >= :since")
    long countGlobalDebitsSince(@Param("since") Instant since);

    /** Barcha muvaffaqiyatsiz TRYON_DEBIT soni: meta null emas va bo'sh emas = xato. */
    @Query(value = "SELECT COUNT(*) FROM credit_transactions "
            + "WHERE type = 'TRYON_DEBIT' AND created_at >= :since "
            + "AND meta IS NOT NULL AND meta <> ''", nativeQuery = true)
    long countGlobalFailedSince(@Param("since") Instant since);

    /** Top N mijozlar TRYON_DEBIT soni bo'yicha (mijoz nomi bilan). */
    @Query(value = "SELECT t.client_id AS client_id, c.name AS name, "
            + "COUNT(*) AS requests, COALESCE(SUM(ABS(t.amount_msim)), 0) AS spent_msim "
            + "FROM credit_transactions t JOIN clients c ON c.id = t.client_id "
            + "WHERE t.type = 'TRYON_DEBIT' "
            + "GROUP BY t.client_id, c.name "
            + "ORDER BY requests DESC "
            + "LIMIT :limit", nativeQuery = true)
    List<GlobalTopClientRow> globalTopClients(@Param("limit") int limit);

    /** Barcha mijozlar bo'yicha global vaqt seriyasi (clientId filtrisiz). */
    @Query(value = "SELECT date_trunc(:bucket, created_at) AS ts, "
            + "COUNT(*) AS cnt, COALESCE(SUM(ABS(amount_msim)), 0) AS spent_msim "
            + "FROM credit_transactions "
            + "WHERE type = 'TRYON_DEBIT' AND created_at >= :since "
            + "GROUP BY 1 ORDER BY 1 ASC", nativeQuery = true)
    List<TimeBucketRow> globalTimeseries(@Param("bucket") String bucket,
                                         @Param("since") Instant since);

    /** Global top client qatori proyeksiyasi. */
    interface GlobalTopClientRow {
        UUID getClientId();
        String getName();
        long getRequests();
        long getSpentMsim();
    }

    /** by-key agregat qatori proyeksiyasi. */
    interface KeyUsageRow {
        UUID getApiKeyId();
        long getRequests();
        long getSpentMsim();
    }

    /** Tarix qatori proyeksiyasi. */
    interface HistoryRow {
        UUID getId();
        Instant getCreatedAt();
        UUID getApiKeyId();
        String getKeyName();
        String getKeyPrefix();
        String getMeta();
        long getSpentMsim();
    }

    /** time-series buket proyeksiyasi. */
    interface TimeBucketRow {
        Instant getTs();
        long getCnt();
        long getSpentMsim();
    }
}
