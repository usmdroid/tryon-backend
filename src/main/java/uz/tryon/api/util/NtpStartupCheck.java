package uz.tryon.api.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Ishga tushganda NTP sinxronizatsiyasini tekshiradi (faqat Linux).
 * Sinxron bo'lmasa — WARN, lekin startup to'xtatilmaydi.
 */
@Component
public class NtpStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NtpStartupCheck.class);

    @Override
    public void run(ApplicationArguments args) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            log.info("NTP check skipped: non-Linux environment");
            return;
        }
        try {
            Process p = new ProcessBuilder(
                    "timedatectl", "show", "--property=NTPSynchronized", "--value")
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            if (!"yes".equalsIgnoreCase(output) && !"true".equalsIgnoreCase(output)) {
                log.warn("NTP not synchronized — Modal HMAC signatures may fail due to clock drift. "
                        + "Fix: `systemctl enable systemd-timesyncd --now`");
            }
        } catch (Exception e) {
            log.warn("NTP check failed: {}", e.getMessage());
        }
    }
}
