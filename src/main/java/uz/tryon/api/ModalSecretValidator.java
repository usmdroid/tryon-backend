package uz.tryon.api;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Startup guard for the Modal HMAC secret.
 * Fails fast if the secret is absent or still the default sentinel,
 * preventing silent deployment with no authentication.
 */
@Component
public class ModalSecretValidator {

    private static final Logger log = LoggerFactory.getLogger(ModalSecretValidator.class);
    private static final String DEFAULT_SENTINEL = "dev-secret-change-me";

    private final AppConfig config;

    public ModalSecretValidator(AppConfig config) {
        this.config = config;
    }

    /**
     * Called by Spring after construction. Throws {@link IllegalStateException} if
     * TRYON_MODAL_SECRET is not configured — app refuses to start without it.
     */
    @PostConstruct
    public void validate() {
        String secret = config.getModalSecret();
        if (secret == null || secret.isBlank() || DEFAULT_SENTINEL.equals(secret)) {
            throw new IllegalStateException(
                    "TRYON_MODAL_SECRET is not set or is still the default sentinel. " +
                    "Set a real secret before starting. " +
                    "See sima-backend/README.md § Modal HMAC secret deploy steps.");
        }
        // Log only the length — never log the secret value itself.
        log.info("Modal secret: OK (len={})", secret.length());
    }
}
