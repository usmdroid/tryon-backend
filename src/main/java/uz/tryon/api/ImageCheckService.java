package uz.tryon.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Rasm generatsiyaga yaroqliligini tekshiradi — Modal'ga (GPU'ga) yubormasdan OLDIN.
 *
 * Bosqichlar:
 *   1-bosqich (hozir): format/hajm + rasm sifati (rezolyutsiya, blur, yorug'lik) — pure Java.
 *   2-bosqich: yuz soni (YuNet, ONNX) — hozircha "skip".
 *   3-bosqich: poza + tana ko'rinishi (MoveNet, ONNX) — hozircha "skip".
 *
 * Har bir tekshiruv {@link CheckItem} qaytaradi; frontend ularni log/Toast'ga aylantiradi.
 */
@Service
public class ImageCheckService {

    private static final Logger log = LoggerFactory.getLogger(ImageCheckService.class);

    private final AppConfig config;
    private final ImageValidator validator;

    public ImageCheckService(AppConfig config, ImageValidator validator) {
        this.config = config;
        this.validator = validator;
    }

    public CheckReport check(String personB64, String clothType) {
        List<CheckItem> checks = new ArrayList<>();

        // 1. Format va hajm (arzon tekshiruv — ImageValidator'ni qayta ishlatamiz)
        ImageValidator.Result fmt = validator.validate(personB64);
        if (!fmt.ok()) {
            checks.add(CheckItem.fail("format", "Format/hajm", fmt.reason()));
            return finish(clothType, checks);
        }
        checks.add(CheckItem.pass("format", "Format/hajm", "Format va hajm joyida."));

        // Rasmni piksellargacha dekod qilish
        BufferedImage img = decode(personB64);
        if (img == null) {
            checks.add(CheckItem.fail("decode", "Rasmni o'qish",
                    "Rasmni dekod qilib bo'lmadi (buzilgan yoki qo'llab-quvvatlanmaydigan format)."));
            return finish(clothType, checks);
        }

        int w = img.getWidth();
        int h = img.getHeight();
        int[] gray = toGrayscale(img);

        // 2. Rezolyutsiya
        checks.add(checkResolution(w, h));
        // 3. Yorug'lik
        checks.add(checkBrightness(gray));
        // 4. Xiralik (blur)
        checks.add(checkBlur(gray, w, h));

        // 5–7. ML tekshiruvlari — keyingi bosqichlarda qo'shiladi
        checks.add(CheckItem.skip("face_count", "Yuz soni", "Keyingi bosqichda (YuNet) qo'shiladi."));
        checks.add(CheckItem.skip("pose", "Poza", "Keyingi bosqichda (MoveNet) qo'shiladi."));
        checks.add(CheckItem.skip("body_coverage", "Tana ko'rinishi",
                "Keyingi bosqichda (MoveNet, cloth_type'ga qarab) qo'shiladi."));

        return finish(clothType, checks);
    }

    private CheckReport finish(String clothType, List<CheckItem> checks) {
        CheckReport report = CheckReport.of(clothType, checks);
        // Serverda ham log qoldiramiz (frontend Toast'idan tashqari, debugging uchun)
        log.info("Rasm tekshiruvi: ok={}, cloth_type={}", report.ok(), clothType);
        for (CheckItem c : checks) {
            log.info("  [{}] {} — {}", c.status(), c.label(), c.message());
        }
        return report;
    }

    // ---- Tekshiruvlar ----

    private CheckItem checkResolution(int w, int h) {
        var c = config.getCheck();
        if (w < c.getMinWidth() || h < c.getMinHeight()) {
            return CheckItem.fail("resolution", "Rezolyutsiya",
                    "Rasm juda kichik (" + w + "x" + h + "). Kamida "
                            + c.getMinWidth() + "x" + c.getMinHeight() + " kerak.");
        }
        return CheckItem.pass("resolution", "Rezolyutsiya", "O'lcham yetarli (" + w + "x" + h + ").");
    }

    private CheckItem checkBrightness(int[] gray) {
        long sum = 0;
        for (int g : gray) sum += g;
        int avg = (int) (sum / gray.length);
        var c = config.getCheck();
        if (avg < c.getBrightnessMin()) {
            return CheckItem.warn("brightness", "Yorug'lik",
                    "Rasm juda qorong'i (o'rtacha yorqinlik " + avg + "). Yorug'roq rasm tavsiya etiladi.");
        }
        if (avg > c.getBrightnessMax()) {
            return CheckItem.warn("brightness", "Yorug'lik",
                    "Rasm juda yorug'/yoritilgan (o'rtacha yorqinlik " + avg + ").");
        }
        return CheckItem.pass("brightness", "Yorug'lik", "Yorug'lik normal (o'rtacha " + avg + ").");
    }

    /** Laplacian dispersiyasi — past bo'lsa rasm xira (fokusda emas). */
    private CheckItem checkBlur(int[] gray, int w, int h) {
        if (w < 3 || h < 3) {
            return CheckItem.skip("blur", "Xiralik", "Rasm xiralik tekshiruvi uchun juda kichik.");
        }
        double sum = 0, sumSq = 0;
        long n = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                int lap = -4 * gray[i] + gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w];
                sum += lap;
                sumSq += (double) lap * lap;
                n++;
            }
        }
        double mean = sum / n;
        double variance = sumSq / n - mean * mean;
        var c = config.getCheck();
        if (variance < c.getBlurMin()) {
            return CheckItem.warn("blur", "Xiralik",
                    "Rasm xira ko'rinadi (fokus o'lchovi " + Math.round(variance) + "). Aniqroq rasm tavsiya etiladi.");
        }
        return CheckItem.pass("blur", "Xiralik", "Rasm yetarlicha aniq (fokus o'lchovi " + Math.round(variance) + ").");
    }

    // ---- Yordamchilar ----

    private BufferedImage decode(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private int[] toGrayscale(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
        int[] gray = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            gray[i] = (int) (0.299 * r + 0.587 * g + 0.114 * b);
        }
        return gray;
    }
}
