package uz.tryon.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Ishga tushganda Sentry holatini log qiladi — ops uchun ko'rinsin. */
@Component
public class SentryStartupLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SentryStartupLogger.class);

    private final String dsn;

    public SentryStartupLogger(@Value("${sentry.dsn:}") String dsn) {
        this.dsn = dsn;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (dsn == null || dsn.isBlank()) {
            log.info("Sentry disabled (SENTRY_DSN not set)");
        } else {
            log.info("Sentry enabled");
        }
    }
}
