package uz.tryon.api.devsandbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DevSandboxKeyService {

    private final DevSandboxKeyRepository repo;
    private final SecureRandom random = new SecureRandom();

    public DevSandboxKeyService(DevSandboxKeyRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public DevSandboxKey generate(UUID createdBy) {
        String key = "dev_" + randomSuffix();
        DevSandboxKey k = new DevSandboxKey(key, createdBy);
        return repo.save(k);
    }

    @Transactional(readOnly = true)
    public List<DevSandboxKey> listForClient(UUID clientId) {
        return repo.findByCreatedByOrderByCreatedAtDesc(clientId);
    }

    @Transactional(readOnly = true)
    public Optional<DevSandboxKey> findByKey(String devKey) {
        return repo.findByDevKey(devKey);
    }

    /** Returns true if increment succeeded (slot was available); false if exhausted or revoked. */
    @Transactional
    public boolean tryIncrement(UUID devKeyId) {
        return repo.tryIncrementUsed(devKeyId, Instant.now()) > 0;
    }

    @Transactional(readOnly = true)
    public List<DevSandboxKey> listAll() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional
    public Optional<DevSandboxKey> revoke(UUID id) {
        Optional<DevSandboxKey> opt = repo.findById(id);
        if (opt.isEmpty()) return Optional.empty();
        DevSandboxKey k = opt.get();
        if (k.getRevokedAt() == null) {
            k.setRevokedAt(Instant.now());
            repo.save(k);
        }
        return Optional.of(k);
    }

    private String randomSuffix() {
        byte[] b = new byte[15];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
