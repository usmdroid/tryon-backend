package uz.tryon.api.account;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SecondaryEmailRepository extends JpaRepository<SecondaryEmail, UUID> {
    List<SecondaryEmail> findByClientId(UUID clientId);
    Optional<SecondaryEmail> findByClientIdAndEmail(UUID clientId, String email);
    boolean existsByEmail(String email);
}
