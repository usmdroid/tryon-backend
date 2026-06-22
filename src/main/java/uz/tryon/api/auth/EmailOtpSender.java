package uz.tryon.api.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.tryon.api.AppConfig;

/**
 * OTP kodini email orqali yetkazadi (faol sender).
 *
 * Graceful fallback: agar email provayder ulanmagan bo'lsa (MAIL_PROVIDER / RESEND_API_KEY /
 * MAIL_FROM bo'sh) — real email yuborilmaydi, faqat dev-fallback yuz berganligi log'ga
 * yoziladi (email manzili; kodning o'zi hech qachon log'ga tushmaydi).
 * Shu tufayli dev/test env'siz ishlayveradi.
 *
 * Domen tayyor bo'lganda: MAIL_PROVIDER=resend, RESEND_API_KEY, MAIL_FROM ni to'ldiring —
 * kodga tegmasdan real email yuborish yoqiladi.
 */
@Component
public class EmailOtpSender implements OtpSender {

    private static final Logger log = LoggerFactory.getLogger(EmailOtpSender.class);

    private final AppConfig config;
    private final EmailClient emailClient; // null = provayder ulanmagan (log rejimi)

    public EmailOtpSender(AppConfig config) {
        this.config = config;
        this.emailClient = buildClient(config);
    }

    /** Provayder sozlangan bo'lsa real klient quradi, aks holda null (log rejimi). */
    private static EmailClient buildClient(AppConfig config) {
        String provider = config.getMailProvider();
        String apiKey = config.getResendApiKey();
        String from = config.getMailFrom();
        // Hamma uchta qiymat to'liq bo'lsa — Resend klientini quramiz.
        if ("resend".equalsIgnoreCase(safe(provider)) && !isBlank(apiKey) && !isBlank(from)) {
            return new ResendEmailClient(apiKey.trim(), from.trim());
        }
        return null;
    }

    @Override
    public void send(String email, String code) {
        if (emailClient == null) {
            // Graceful fallback — provayder yo'q, real email yuborilmaydi (dev/test).
            // Xavfsizlik: kod hech qachon log'ga yozilmaydi (prod log agregatoriga tushmasin).
            log.info("OTP yuborilmadi (dev fallback): {}", email);
            return;
        }
        // Provayder sozlangan — real email yuboramiz.
        long minutes = Math.max(1, config.getOtpTtlSeconds() / 60);
        String subject = OtpEmailTemplate.SUBJECT;
        String html = OtpEmailTemplate.html(code, minutes);
        String text = OtpEmailTemplate.text(code, minutes);
        emailClient.send(email, subject, html, text);
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
