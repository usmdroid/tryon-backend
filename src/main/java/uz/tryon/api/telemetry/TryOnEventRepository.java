package uz.tryon.api.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TryOnEventRepository extends JpaRepository<TryOnEvent, UUID> {

    /** Kunlik yig'indi: har bir kun uchun count/success/fail. */
    @Query(value = "SELECT to_char(date_trunc('day', ts), 'YYYY-MM-DD') AS key, "
            + "COUNT(*) AS count, "
            + "SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) AS success, "
            + "SUM(CASE WHEN result = 'fail' THEN 1 ELSE 0 END) AS fail "
            + "FROM tryon_events "
            + "WHERE partner_id = CAST(:partnerId AS uuid) AND ts >= :from AND ts <= :to "
            + "GROUP BY 1 ORDER BY 1 ASC", nativeQuery = true)
    List<BucketRow> aggregateByDay(@Param("partnerId") UUID partnerId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to);

    /** Manba (origin) bo'yicha yig'indi. */
    @Query(value = "SELECT origin AS key, "
            + "COUNT(*) AS count, "
            + "SUM(CASE WHEN result = 'success' THEN 1 ELSE 0 END) AS success, "
            + "SUM(CASE WHEN result = 'fail' THEN 1 ELSE 0 END) AS fail "
            + "FROM tryon_events "
            + "WHERE partner_id = CAST(:partnerId AS uuid) AND ts >= :from AND ts <= :to "
            + "GROUP BY origin ORDER BY origin ASC", nativeQuery = true)
    List<BucketRow> aggregateBySource(@Param("partnerId") UUID partnerId,
                                       @Param("from") Instant from,
                                       @Param("to") Instant to);

    /** Kun bo'yicha sahifalangan tarix. */
    @Query(value = "SELECT CAST(id AS VARCHAR) AS id, ts, platform, result, origin, "
            + "product_id, product_name, cloth_type, fail_reason, device_id, client_ip "
            + "FROM tryon_events "
            + "WHERE partner_id = CAST(:partnerId AS uuid) AND ts >= :from AND ts <= :to "
            + "  AND to_char(date_trunc('day', ts), 'YYYY-MM-DD') = :key "
            + "ORDER BY ts DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<EventRow> historyByDay(@Param("partnerId") UUID partnerId,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to,
                                 @Param("key") String key,
                                 @Param("limit") int limit,
                                 @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM tryon_events "
            + "WHERE partner_id = CAST(:partnerId AS uuid) AND ts >= :from AND ts <= :to "
            + "  AND to_char(date_trunc('day', ts), 'YYYY-MM-DD') = :key", nativeQuery = true)
    long countHistoryByDay(@Param("partnerId") UUID partnerId,
                            @Param("from") Instant from,
                            @Param("to") Instant to,
                            @Param("key") String key);

    /** Manba bo'yicha sahifalangan tarix. */
    @Query(value = "SELECT CAST(id AS VARCHAR) AS id, ts, platform, result, origin, "
            + "product_id, product_name, cloth_type, fail_reason, device_id, client_ip "
            + "FROM tryon_events "
            + "WHERE partner_id = CAST(:partnerId AS uuid) AND ts >= :from AND ts <= :to "
            + "  AND origin = :key "
            + "ORDER BY ts DESC LIMIT :limit OFFSET :offset", nativeQuery = true)
    List<EventRow> historyBySource(@Param("partnerId") UUID partnerId,
                                    @Param("from") Instant from,
                                    @Param("to") Instant to,
                                    @Param("key") String key,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    @Query(value = "SELECT COUNT(*) FROM tryon_events "
            + "WHERE partner_id = CAST(:partnerId AS uuid) AND ts >= :from AND ts <= :to "
            + "  AND origin = :key", nativeQuery = true)
    long countHistoryBySource(@Param("partnerId") UUID partnerId,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               @Param("key") String key);

    /** Kun/manba yig'indi qatori proyeksiyasi. */
    interface BucketRow {
        String getKey();
        long getCount();
        long getSuccess();
        long getFail();
    }

    /** Tarix voqea qatori proyeksiyasi. */
    interface EventRow {
        String getId();
        Instant getTs();
        String getPlatform();
        String getResult();
        String getOrigin();
        String getProductId();
        String getProductName();
        String getClothType();
        String getFailReason();
        String getDeviceId();
        String getClientIp();
    }

    /** Eng ko'p sinab ko'rilgan mahsulotlar (partner bo'yicha). */
    @Query(value = "SELECT product_id AS productId, MAX(product_name) AS productName, COUNT(*) AS count "
            + "FROM tryon_events "
            + "WHERE partner_id = CAST(:partnerId AS uuid) AND ts >= :from AND ts <= :to "
            + "  AND product_id IS NOT NULL AND product_id <> '' "
            + "GROUP BY product_id ORDER BY count DESC LIMIT :limit", nativeQuery = true)
    List<TopProductRow> topProducts(@Param("partnerId") UUID partnerId,
                                     @Param("from") Instant from,
                                     @Param("to") Instant to,
                                     @Param("limit") int limit);

    interface TopProductRow {
        String getProductId();
        String getProductName();
        long getCount();
    }

    /** GPU xarajat yig'indisi: muaffaqiyatli so'rovlar soni va jami davomiyligi. */
    @Query(value = "SELECT COUNT(*) AS requestCount, SUM(duration_ms) AS totalDurationMs "
            + "FROM tryon_events "
            + "WHERE result = 'success' AND ts >= :from AND ts <= :to", nativeQuery = true)
    GpuCostRow gpuCostAggregate(@Param("from") Instant from, @Param("to") Instant to);

    interface GpuCostRow {
        long getRequestCount();
        Long getTotalDurationMs();
    }
}
