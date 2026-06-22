package uz.tryon.api.auth;

/**
 * Email yuborish abstraksiyasi (pluggable). Hozir: ResendEmailClient.
 * Boshqa provayder (SES, Postmark) qo'shilsa — shu interfeysni amalga oshiradi.
 */
public interface EmailClient {
    /**
     * Email yuboradi.
     * @param to       qabul qiluvchi manzil
     * @param subject  mavzu (sarlavha)
     * @param htmlBody HTML ko'rinish
     * @param textBody oddiy matn (HTML qo'llab-quvvatlanmasa)
     */
    void send(String to, String subject, String htmlBody, String textBody);
}
