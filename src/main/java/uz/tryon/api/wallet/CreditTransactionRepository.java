package uz.tryon.api.wallet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, UUID> {

    List<CreditTransaction> findByClientIdOrderByCreatedAtDesc(UUID clientId, Pageable pageable);

    boolean existsByClientIdAndType(UUID clientId, String type);
}
