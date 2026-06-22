package uz.tryon.api.auth;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import uz.tryon.api.AppConfig;

/**
 * Production xavfsizlik qalqoni: agar "prod" profili faol bo'lsa-yu, qat'iy OTP kod
 * ({@code tryon.otp-fixed-code}) tozalanmagan bo'lsa — ilova ishga tushmaydi.
 *
 * Sababi: qat'iy kod (masalan "123456") prod'da qolib ketsa, istalgan kishi shu kod bilan
 * ro'yxatdan o'tib oladi. Tekshiruv {@code @PostConstruct}'da — bean ishga tushayotganda,
 * ya'ni web-server ulanishlarni qabul qila boshlashidan oldin — bajariladi. Shu sababli xato
 * sozlama bilan ilova bir lahza ham "tirik" bo'lmaydi (fail-closed): kontekst startida to'xtaydi.
 *
 * Dev/test profillarida hech narsa qilmaydi (mavjud xulq o'zgarmaydi).
 */
@Component
public class OtpFixedCodeSafeguard {

    private static final Logger log = LoggerFactory.getLogger(OtpFixedCodeSafeguard.class);

    private final Environment environment;
    private final AppConfig config;

    public OtpFixedCodeSafeguard(Environment environment, AppConfig config) {
        this.environment = environment;
        this.config = config;
    }

    @PostConstruct
    public void verifyOtpFixedCodeNotSetInProd() {
        boolean prodActive = environment.acceptsProfiles(Profiles.of("prod"));
        if (!prodActive) {
            return; // dev/test — hech narsa qilmaymiz
        }
        String fixed = config.getOtpFixedCode();
        if (fixed != null && !fixed.isBlank()) {
            throw new IllegalStateException(
                    "Xavfsizlik xatosi: 'prod' profilida tryon.otp-fixed-code (TRYON_OTP_FIXED_CODE) "
                            + "o'rnatilgan. Bu istalgan kishi qat'iy kod bilan ro'yxatdan o'tishiga yo'l qo'yadi. "
                            + "Ilova to'xtatildi — TRYON_OTP_FIXED_CODE ni bo'sh qiling.");
        }
        log.info("OTP fixed-code qalqoni: prod profilida qat'iy kod o'rnatilmagan — OK.");
    }
}
