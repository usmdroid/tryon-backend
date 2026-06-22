package uz.tryon.api.auth;

/**
 * OTP email shabloni (Sima brendi).
 *
 * Email klientlari (Gmail, Outlook) <style> bloklarini e'tiborsiz qoldiradi —
 * shuning uchun barcha CSS inline (style="...") yoziladi. Plain-text variant ham bor.
 * Mustaqil klass — testdan oson chaqirish uchun.
 */
public final class OtpEmailTemplate {
    private OtpEmailTemplate() { }

    /** Mavzu (sarlavha) — o'zbekcha. */
    public static final String SUBJECT = "Sima — tasdiqlash kodingiz";

    /** HTML ko'rinish (inline CSS). minutes — kod necha daqiqa amal qilishi. */
    public static String html(String code, long minutes) {
        return """
                <!DOCTYPE html>
                <html lang="uz">
                <body style="margin:0;padding:0;background-color:#f4f5f7;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;">
                  <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f5f7;padding:32px 0;">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="440" cellpadding="0" cellspacing="0" style="background-color:#ffffff;border-radius:16px;overflow:hidden;box-shadow:0 2px 8px rgba(0,0,0,0.06);">
                          <tr>
                            <td style="background-color:#111827;padding:28px 32px;text-align:center;">
                              <span style="color:#ffffff;font-size:26px;font-weight:700;letter-spacing:1px;">Sima</span>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:36px 32px 12px 32px;text-align:center;">
                              <p style="margin:0 0 8px 0;color:#374151;font-size:16px;">Tasdiqlash kodingiz:</p>
                              <div style="margin:20px auto;display:inline-block;background-color:#f3f4f6;border-radius:12px;padding:18px 28px;">
                                <span style="color:#111827;font-size:40px;font-weight:700;letter-spacing:10px;">%s</span>
                              </div>
                              <p style="margin:16px 0 0 0;color:#6b7280;font-size:14px;">%d daqiqa amal qiladi</p>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:20px 32px 32px 32px;text-align:center;">
                              <p style="margin:0;color:#9ca3af;font-size:12px;line-height:18px;">
                                Agar bu kodni siz so'ramagan bo'lsangiz, bu xabarni e'tiborsiz qoldiring.
                              </p>
                            </td>
                          </tr>
                          <tr>
                            <td style="background-color:#f9fafb;padding:18px 32px;text-align:center;border-top:1px solid #eef0f3;">
                              <span style="color:#9ca3af;font-size:12px;">© Sima</span>
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(code, minutes);
    }

    /** Oddiy matn varianti (HTML qo'llab-quvvatlanmasa). */
    public static String text(String code, long minutes) {
        return "Sima\n\n"
                + "Tasdiqlash kodingiz: " + code + "\n"
                + minutes + " daqiqa amal qiladi.\n\n"
                + "Agar bu kodni siz so'ramagan bo'lsangiz, bu xabarni e'tiborsiz qoldiring.";
    }
}
