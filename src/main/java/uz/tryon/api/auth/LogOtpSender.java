package uz.tryon.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dev OTP sender — kodni server log'iga chiqaradi (tashqi xizmatsiz test/demo uchun).
 * Production'da Telegram/SMS sender bilan almashtiriladi.
 */
@Component
public class LogOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(LogOtpSender.class);

    @Override
    public void send(String phone, String code) {
        log.info("📲 OTP [{}] => {}  (dev — log sender)", phone, code);
    }
}
