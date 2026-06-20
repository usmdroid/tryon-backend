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
            + "GROUP BY date_trunc(:bucket, created_at) "
            + "ORDER BY date_trunc(:bucket, created_at) ASC", nativeQuery = true)
    List<TimeBucketRow> aggregateTimeseries(@Param("clientId") UUID clientId,
                                            @Param("bucket") String bucket,
                                            @Param("since") Instant since,
                                            @Param("apiKeyId") String apiKeyId);

    /** by-key agregat qatori proyeksiyasi. */
    interface KeyUsageRow {
        UUID getApiKeyId();
        long getRequests();
        long getSpentMsim();
    }

    /** time-series buket proyeksiyasi. */
    interface TimeBucketRow {
        Instant getTs();
        long getCnt();
        long getSpentMsim();
    }
}
