package uz.tryon.api;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MoveNet MultiPose Lightning (ONNX) orqali poza detektsiyasi.
 *
 * Model bir rasmda 6 tagacha odamni topadi, har biriga 17 ta tana nuqtasi beradi.
 * Bundan biz: odamlar/yuzlar sonini, pozani va tana ko'rinishini aniqlaymiz.
 *
 * Kirish:  int32 NHWC [1, H, W, 3], H/W — 32 ga karrali, qiymatlar 0..255 (RGB).
 * Chiqish: float [1, 6, 56] — har odam uchun 17*(y,x,score) + (ymin,xmin,ymax,xmax,score).
 * Koordinatalar [0,1] — to'ldirilgan (padded) kanvasga nisbatan normallashtirilgan.
 */
@Service
public class PoseDetector {

    private static final Logger log = LoggerFactory.getLogger(PoseDetector.class);

    // COCO 17 nuqta indekslari
    public static final int NOSE = 0, L_EYE = 1, R_EYE = 2, L_EAR = 3, R_EAR = 4,
            L_SHOULDER = 5, R_SHOULDER = 6, L_ELBOW = 7, R_ELBOW = 8, L_WRIST = 9, R_WRIST = 10,
            L_HIP = 11, R_HIP = 12, L_KNEE = 13, R_KNEE = 14, L_ANKLE = 15, R_ANKLE = 16;

    /** Modelni eng uzun tomonni shu o'lchamga keltirib beramiz (Lightning uchun tavsiya). */
    private static final int TARGET = 256;
    private static final int MULT = 32; // o'lcham shunga karrali bo'lishi shart

    private final OrtEnvironment env;
    private final OrtSession session;
    private final boolean ready;

    public PoseDetector() {
        OrtEnvironment e = null;
        OrtSession s = null;
        boolean ok = false;
        try {
            byte[] model;
            try (InputStream in = getClass().getResourceAsStream("/models/movenet-multipose.onnx")) {
                if (in == null) throw new IllegalStateException("Model topilmadi: /models/movenet-multipose.onnx");
                model = in.readAllBytes();
            }
            e = OrtEnvironment.getEnvironment();
            s = e.createSession(model, new OrtSession.SessionOptions());
            ok = true;
            log.info("MoveNet modeli yuklandi ({} bayt).", model.length);
        } catch (Throwable t) {
            log.error("MoveNet modelini yuklab bo'lmadi — poza tekshiruvlari o'tkazib yuboriladi.", t);
        }
        this.env = e;
        this.session = s;
        this.ready = ok;
    }

    public boolean isReady() { return ready; }

    /** Bitta aniqlangan odam (normallashtirilgan koordinatalarda). */
    public record Keypoint(double y, double x, double score) {
        public boolean visible(double t) { return score >= t; }
    }

    public record Person(Keypoint[] keypoints, double boxScore) {}

    /**
     * Topilgan odamlar + asl rasm to'ldirilgan kanvasda egallagan ulush (fracW/fracH).
     * Normallashtirilgan koordinatani asl rasmga keltirish: origNorm = paddedNorm / frac.
     */
    public record PoseResult(List<Person> persons, double fracW, double fracH) {}

    public PoseResult detect(BufferedImage src) {
        if (!ready) return new PoseResult(List.of(), 1, 1);

        int w = src.getWidth(), h = src.getHeight();
        double scale = (double) TARGET / Math.max(w, h);
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));
        int padW = ceilTo(newW, MULT);
        int padH = ceilTo(newH, MULT);

        // Asl rasmni o'lchamini o'zgartirib, qora kanvasning chap-yuqorisiga joylaymiz
        BufferedImage canvas = new BufferedImage(padW, padH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, padW, padH);
        g.drawImage(src, 0, 0, newW, newH, null);
        g.dispose();

        int[] rgb = canvas.getRGB(0, 0, padW, padH, null, 0, padW);
        int[] data = new int[padW * padH * 3];
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            data[i * 3]     = (p >> 16) & 0xFF; // R
            data[i * 3 + 1] = (p >> 8) & 0xFF;  // G
            data[i * 3 + 2] = p & 0xFF;         // B
        }

        try (OnnxTensor input = OnnxTensor.createTensor(env, IntBuffer.wrap(data),
                new long[]{1, padH, padW, 3});
             OrtSession.Result result = session.run(Map.of("input", input))) {

            float[][][] out = (float[][][]) result.get(0).getValue(); // [1][6][56]
            List<Person> persons = new ArrayList<>();
            for (float[] inst : out[0]) {
                double boxScore = inst[55];
                Keypoint[] kps = new Keypoint[17];
                for (int k = 0; k < 17; k++) {
                    kps[k] = new Keypoint(inst[k * 3], inst[k * 3 + 1], inst[k * 3 + 2]);
                }
                persons.add(new Person(kps, boxScore));
            }
            return new PoseResult(persons, (double) newW / padW, (double) newH / padH);

        } catch (Exception ex) {
            log.error("Poza inference xatosi.", ex);
            return new PoseResult(List.of(), 1, 1);
        }
    }

    private static int ceilTo(int v, int m) {
        return ((v + m - 1) / m) * m;
    }

    @PreDestroy
    public void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
    }
}
