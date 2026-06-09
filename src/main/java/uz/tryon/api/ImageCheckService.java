package uz.tryon.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static uz.tryon.api.PoseDetector.*;

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
    private final PoseDetector pose;

    public ImageCheckService(AppConfig config, ImageValidator validator, PoseDetector pose) {
        this.config = config;
        this.validator = validator;
        this.pose = pose;
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

        // 5–7. Poza asosidagi tekshiruvlar (MoveNet): yuz soni, poza, tana ko'rinishi
        checks.addAll(poseChecks(img, clothType));

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

    // ---- Poza asosidagi tekshiruvlar (MoveNet) ----

    private List<CheckItem> poseChecks(BufferedImage img, String clothType) {
        if (!pose.isReady()) {
            return List.of(
                    CheckItem.skip("face_count", "Yuz soni", "Poza modeli yuklanmagan."),
                    CheckItem.skip("pose", "Poza", "Poza modeli yuklanmagan."),
                    CheckItem.skip("body_coverage", "Tana ko'rinishi", "Poza modeli yuklanmagan."));
        }

        PoseDetector.PoseResult res = pose.detect(img);
        double pmin = config.getCheck().getPersonScoreMin();
        double kmin = config.getCheck().getKeypointScoreMin();

        List<Person> people = res.persons().stream().filter(p -> p.boxScore() >= pmin).toList();
        List<Person> faces = people.stream().filter(p -> faceVisible(p, kmin)).toList();

        List<CheckItem> out = new ArrayList<>();

        // Yuz/odam soni — avval gavda (odam) bo'yicha sanaymiz, keyin yuz ko'rinishi.
        // Shunda yuzi ko'rinmaydigan 2-odam ham aniqlanadi.
        if (people.size() > 1) {
            out.add(CheckItem.fail("face_count", "Yuz soni",
                    "Rasmda bir nechta odam topildi (" + people.size() + "). Faqat bitta odam bo'lsin."));
        } else if (people.isEmpty()) {
            out.add(CheckItem.fail("face_count", "Yuz soni",
                    "Rasmda odam topilmadi. Butun gavda, old tomondan olingan rasm yuklang."));
        } else if (faces.isEmpty()) {
            out.add(CheckItem.fail("face_count", "Yuz soni",
                    "Odam yuzi ko'rinmadi. Old tomondan, yuzi ko'rinadigan rasm yuklang."));
        } else {
            out.add(CheckItem.pass("face_count", "Yuz soni", "Bitta odam aniqlandi."));
        }

        // Subyekt: aniq bitta odam bo'lsa o'sha, aks holda eng ishonchli odam
        Person subject = people.size() == 1 ? people.get(0)
                : people.stream().max(Comparator.comparingDouble(Person::boxScore)).orElse(null);

        if (subject == null) {
            out.add(CheckItem.fail("pose", "Poza", "Odam aniqlanmadi."));
            out.add(CheckItem.fail("body_coverage", "Tana ko'rinishi", "Odam aniqlanmadi."));
            return out;
        }

        out.add(poseCheck(subject, kmin));
        out.add(coverageCheck(subject, clothType, kmin));
        return out;
    }

    private boolean faceVisible(Person p, double k) {
        Keypoint[] kp = p.keypoints();
        return kp[NOSE].visible(k) && (kp[L_EYE].visible(k) || kp[R_EYE].visible(k));
    }

    /** Tik turibdimi / yotmaganmi / old tomondanmi. */
    private CheckItem poseCheck(Person p, double k) {
        Keypoint[] kp = p.keypoints();
        int shoulders = (kp[L_SHOULDER].visible(k) ? 1 : 0) + (kp[R_SHOULDER].visible(k) ? 1 : 0);
        int hips = (kp[L_HIP].visible(k) ? 1 : 0) + (kp[R_HIP].visible(k) ? 1 : 0);

        if (shoulders == 0 || hips == 0) {
            return CheckItem.warn("pose", "Poza",
                    "Pozani aniq baholab bo'lmadi (yelka yoki bel ko'rinmadi).");
        }

        double shMidY = avg(kp, L_SHOULDER, R_SHOULDER, k, true);
        double shMidX = avg(kp, L_SHOULDER, R_SHOULDER, k, false);
        double hipMidY = avg(kp, L_HIP, R_HIP, k, true);
        double hipMidX = avg(kp, L_HIP, R_HIP, k, false);

        double dy = hipMidY - shMidY;          // tik turganda bel yelkadan pastda (dy > 0)
        double dx = Math.abs(hipMidX - shMidX);

        if (dy <= 0 || dx > dy) {
            return CheckItem.fail("pose", "Poza",
                    "Odam tik turmagan (yotgan yoki kuchli egilgan) ko'rinadi.");
        }
        if (shoulders == 1) {
            return CheckItem.warn("pose", "Poza",
                    "Yon tomondan turgan ko'rinadi. Old tomondan turish tavsiya etiladi.");
        }
        return CheckItem.pass("pose", "Poza", "Poza yaroqli (tik va old tomondan).");
    }

    /** cloth_type'ga qarab kerakli tana qismlari ko'rinyaptimi. */
    private CheckItem coverageCheck(Person p, String clothType, double k) {
        Keypoint[] kp = p.keypoints();
        boolean bothShoulders = kp[L_SHOULDER].visible(k) && kp[R_SHOULDER].visible(k);
        boolean anyShoulder = kp[L_SHOULDER].visible(k) || kp[R_SHOULDER].visible(k);
        boolean hip = kp[L_HIP].visible(k) || kp[R_HIP].visible(k);
        boolean knee = kp[L_KNEE].visible(k) || kp[R_KNEE].visible(k);
        boolean ankle = kp[L_ANKLE].visible(k) || kp[R_ANKLE].visible(k);
        String t = clothType == null ? "upper" : clothType.toLowerCase();

        if (t.equals("upper")) {
            return (bothShoulders && hip)
                    ? CheckItem.pass("body_coverage", "Tana ko'rinishi", "Ustki tana ko'rinmoqda (yelkadan belgacha).")
                    : CheckItem.fail("body_coverage", "Tana ko'rinishi",
                    "Ustki kiyim uchun yelkadan belgacha ko'rinishi kerak.");
        }
        if (t.equals("lower")) {
            return (hip && (knee || ankle))
                    ? CheckItem.pass("body_coverage", "Tana ko'rinishi", "Pastki tana ko'rinmoqda (beldan oyoqgacha).")
                    : CheckItem.fail("body_coverage", "Tana ko'rinishi",
                    "Pastki kiyim uchun beldan oyoqgacha ko'rinishi kerak.");
        }
        // full / overall / dress / boshqa — butun bo'y kerak
        return (anyShoulder && hip && (knee || ankle))
                ? CheckItem.pass("body_coverage", "Tana ko'rinishi", "Butun tana ko'rinmoqda.")
                : CheckItem.fail("body_coverage", "Tana ko'rinishi",
                "Bu kiyim uchun butun bo'y (yelkadan oyoqgacha) ko'rinishi kerak.");
    }

    /** a va b nuqtalarning ko'rinadiganlari bo'yicha o'rtacha (yAxis=true → y, aks holda x). */
    private double avg(Keypoint[] kp, int a, int b, double k, boolean yAxis) {
        double sum = 0;
        int n = 0;
        if (kp[a].visible(k)) { sum += yAxis ? kp[a].y() : kp[a].x(); n++; }
        if (kp[b].visible(k)) { sum += yAxis ? kp[b].y() : kp[b].x(); n++; }
        return n == 0 ? 0 : sum / n;
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
