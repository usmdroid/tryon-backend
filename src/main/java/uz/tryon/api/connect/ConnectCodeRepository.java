package uz.tryon.api.connect;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConnectCodeRepository extends JpaRepository<ConnectCode, UUID> {

    Optional<ConnectCode> findByCodeHash(String codeHash);

    /**
     * Kodni bir martalik atomik tarzda iste'mol qiladi (consumed_at = null bo'lgan holda).
     * Qaytarilgan qator soni: 1 = muvaffaqiyatli, 0 = allaqachon ishlatilgan yoki topilmadi.
     */
    @Modifying
    @Transactional
    @Query("UPDATE ConnectCode c SET c.consumedAt = :now WHERE c.codeHash = :hash AND c.consumedAt IS NULL")
    int consumeByHash(@Param("hash") String hash, @Param("now") Instant now);
}
