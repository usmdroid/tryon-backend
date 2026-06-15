package uz.tryon.api.auth;

/**
 * OTP kodini yetkazish kanali (pluggable). Hozir: LogOtpSender (dev).
 * Keyin: TelegramOtpSender / SmsOtpSender — shu interfeysni amalga oshiradi.
 */
public interface OtpSender {
    void send(String phone, String code);
}
