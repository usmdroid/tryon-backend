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
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * YOLOv8n (COCO, ONNX) orqali ODAM detektsiyasi — "rasmda nechta odam bor" savoliga
 * MoveNet'dan ishonchliroq javob beradi.
 *
 * Kirish:  float32 [1, 3, 640, 640], RGB, 0..1, letterbox (nisbatni saqlab 640x640 ga).
 * Chiqish: float [1, 84, 8400] — 4 box (cx,cy,w,h) + 80 klass; odam = 0-klass (indeks 4).
 */
@Service
public class PersonDetector {

    private static final Logger log = LoggerFactory.getLogger(PersonDetector.class);

    private static final int SIZE = 640;
    private static final int PERSON_CLASS = 0;
    private static final float CONF = 0.40f;
    private static final float IOU = 0.45f;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final boolean ready;

    public PersonDetector() {
        OrtEnvironment e = null;
        OrtSession s = null;
        String in = "images";
        boolean ok = false;
        try {
            byte[] model;
            try (InputStream is = getClass().getResourceAsStream("/models/yolov8n.onnx")) {
                if (is == null) throw new IllegalStateException("Model topilmadi: /models/yolov8n.onnx");
                model = is.readAllBytes();
            }
            e = OrtEnvironment.getEnvironment();
            s = e.createSession(model, new OrtSession.SessionOptions());
            in = s.getInputNames().iterator().next();
            ok = true;
            log.info("YOLOv8 modeli yuklandi ({} bayt), input='{}'.", model.length, in);
        } catch (Throwable t) {
            log.error("YOLOv8 modelini yuklab bo'lmadi — odam sanash o'tkazib yuboriladi.", t);
        }
        this.env = e;
        this.session = s;
        this.inputName = in;
        this.ready = ok;
    }

    public boolean isReady() { return ready; }

    /** Topilgan odam: asl rasm koordinatalarida quti + ishonch. */
    public record Box(double x1, double y1, double x2, double y2, double score) {}

    /** Rasmda topilgan odamlar ro'yxati (NMS qilingan). */
    public List<Box> detect(BufferedImage src) {
        if (!ready) return List.of();
        int w = src.getWidth(), h = src.getHeight();
        double r = Math.min((double) SIZE / w, (double) SIZE / h);
        int nw = (int) Math.round(w * r), nh = (int) Math.round(h * r);
        int padX = (SIZE - nw) / 2, padY = (SIZE - nh) / 2;

        BufferedImage canvas = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(114, 114, 114)); // YOLO letterbox to'ldirish rangi
        g.fillRect(0, 0, SIZE, SIZE);
        g.drawImage(src, padX, padY, nw, nh, null);
        g.dispose();

        int[] rgb = canvas.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
        float[] data = new float[3 * SIZE * SIZE]; // CHW
        int plane = SIZE * SIZE;
        for (int i = 0; i < rgb.length; i++) {
            int p = rgb[i];
            data[i]             = ((p >> 16) & 0xFF) / 255f; // R
            data[plane + i]     = ((p >> 8) & 0xFF) / 255f;  // G
            data[2 * plane + i] = (p & 0xFF) / 255f;         // B
        }

        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data),
                new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Map.of(inputName, input))) {

            float[][][] out = (float[][][]) result.get(0).getValue(); // [1][84][8400]
            float[][] o = out[0];
            int feats = o.length;       // 84
            int anchors = o[0].length;  // 8400
            List<Box> boxes = new ArrayList<>();
            for (int i = 0; i < anchors; i++) {
                float score = o[4 + PERSON_CLASS][i];
                if (score < CONF) continue;
                double cx = o[0][i], cy = o[1][i], bw = o[2][i], bh = o[3][i];
                double x1 = (cx - bw / 2 - padX) / r;
                double y1 = (cy - bh / 2 - padY) / r;
                double x2 = (cx + bw / 2 - padX) / r;
                double y2 = (cy + bh / 2 - padY) / r;
                boxes.add(new Box(x1, y1, x2, y2, score));
            }
            return nms(boxes);

        } catch (Exception ex) {
            log.error("YOLO inference xatosi.", ex);
            return List.of();
        }
    }

    /** Non-Maximum Suppression — bir-biriga ko'p mos qutilarni birlashtiradi. */
    private List<Box> nms(List<Box> boxes) {
        boxes.sort(Comparator.comparingDouble(Box::score).reversed());
        List<Box> keep = new ArrayList<>();
        boolean[] removed = new boolean[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            if (removed[i]) continue;
            Box a = boxes.get(i);
            keep.add(a);
            for (int j = i + 1; j < boxes.size(); j++) {
                if (removed[j]) continue;
                if (iou(a, boxes.get(j)) > IOU) removed[j] = true;
            }
        }
        return keep;
    }

    private double iou(Box a, Box b) {
        double ix1 = Math.max(a.x1, b.x1), iy1 = Math.max(a.y1, b.y1);
        double ix2 = Math.min(a.x2, b.x2), iy2 = Math.min(a.y2, b.y2);
        double iw = Math.max(0, ix2 - ix1), ih = Math.max(0, iy2 - iy1);
        double inter = iw * ih;
        double areaA = Math.max(0, a.x2 - a.x1) * Math.max(0, a.y2 - a.y1);
        double areaB = Math.max(0, b.x2 - b.x1) * Math.max(0, b.y2 - b.y1);
        double union = areaA + areaB - inter;
        return union <= 0 ? 0 : inter / union;
    }

    @PreDestroy
    public void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
    }
}
