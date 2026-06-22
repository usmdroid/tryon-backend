package uz.tryon.api.auth;

/**
 * OTP kodini yetkazish kanali (pluggable). Hozir: EmailOtpSender (email orqali).
 *
 * Kod email manziliga yuboriladi. Provayder ulanmagan bo'lsa (dev/test),
 * EmailOtpSender real email yubormaydi — faqat dev-fallback yuz berganini
 * log'ga yozadi (email manzili, kodning o'zi hech qachon log'ga tushmaydi).
 */
public interface OtpSender {
    /** Tasdiqlash kodini berilgan email manziliga yuboradi. */
    void send(String email, String code);
}
