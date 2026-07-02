package uz.tryon.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.tryon.api.PoseDetector.*;

/**
 * To'rt signalli orqa/yon yuzlanish aniqlovchisi uchun birlik testlari.
 * ONNX modeli shart emas — keypoint massivlari qo'lda quriladi.
 *
 * Chegaralar: faceEarRatioMin=0.5, eyeStrong=0.4, noseStrong=0.3, shoulderAsym=0.3
 */
class FacingDetectionTest {

    private static final double FACE_EAR   = 0.5;
    private static final double EYE_STRONG = 0.4;
    private static final double NOSE_STRONG = 0.3;
    private static final double SH_ASYM    = 0.3;

    /** Barcha 17 nuqtani 0.0 score bilan yasaydi. */
    private static PoseDetector.Keypoint[] blank() {
        PoseDetector.Keypoint[] kp = new PoseDetector.Keypoint[17];
        for (int i = 0; i < 17; i++) kp[i] = new PoseDetector.Keypoint(0.5, 0.5, 0.0);
        return kp;
    }

    private static ImageCheckService.Facing detect(PoseDetector.Keypoint[] kp) {
        return ImageCheckService.detectFacing(kp, FACE_EAR, EYE_STRONG, NOSE_STRONG, SH_ASYM);
    }

    // ---- pure_front: barcha yuz signallari kuchli, simmetrik yelkalar ----
    @Test
    void pure_front_returnsFRONT() {
        PoseDetector.Keypoint[] kp = blank();
        // Kuchli yuz, zaif quloq, simmetrik yelkalar
        kp[NOSE]      = new PoseDetector.Keypoint(0.10, 0.50, 0.90);
        kp[L_EYE]     = new PoseDetector.Keypoint(0.10, 0.45, 0.85);
        kp[R_EYE]     = new PoseDetector.Keypoint(0.10, 0.55, 0.88);
        kp[L_EAR]     = new PoseDetector.Keypoint(0.10, 0.38, 0.05);
        kp[R_EAR]     = new PoseDetector.Keypoint(0.10, 0.62, 0.05);
        kp[L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, 0.80);
        kp[R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, 0.80);
        // Signal 1: faceAvg=0.877, earAvg=0.05, ratio=17.5 > 0.5 → no back
        // Signal 2: strongEyes=2 → no back
        // Signal 3: nose=0.90 >= 0.3 → no back
        // Signal 4: |0.80-0.80|=0.0 <= 0.3 → no side
        assertThat(detect(kp)).isEqualTo(ImageCheckService.Facing.FRONT);
    }

    // ---- slightly_turned_front: faqat burun score chegaradan past (1 orqa ovoz) → UNCERTAIN ----
    @Test
    void slightly_turned_front_returnsUNCERTAIN() {
        PoseDetector.Keypoint[] kp = blank();
        // Ko'zlar kuchli, burun biroz zaif — bittadan orqa ovoz
        kp[NOSE]      = new PoseDetector.Keypoint(0.10, 0.50, 0.20); // < 0.3 → signal 3
        kp[L_EYE]     = new PoseDetector.Keypoint(0.10, 0.45, 0.85);
        kp[R_EYE]     = new PoseDetector.Keypoint(0.10, 0.55, 0.88);
        kp[L_EAR]     = new PoseDetector.Keypoint(0.10, 0.38, 0.08);
        kp[R_EAR]     = new PoseDetector.Keypoint(0.10, 0.62, 0.08);
        kp[L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, 0.80);
        kp[R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, 0.80);
        // Signal 1: faceAvg=(0.20+0.85+0.88)/3=0.643, earAvg=0.08, ratio=8.0 > 0.5 → no back
        // Signal 2: strongEyes=2 → no back
        // Signal 3: nose=0.20 < 0.3 → +1 back
        // Signal 4: |0.80-0.80|=0.0 → no side
        // backVotes=1 → UNCERTAIN
        assertThat(detect(kp)).isEqualTo(ImageCheckService.Facing.UNCERTAIN);
    }

    // ---- pure_back: barcha 3 yuz signali zaif, kuchli quloqlar (3 orqa ovoz) → BACK ----
    @Test
    void pure_back_returnsBACK() {
        PoseDetector.Keypoint[] kp = blank();
        kp[NOSE]      = new PoseDetector.Keypoint(0.10, 0.50, 0.05); // < 0.3 → signal 3
        kp[L_EYE]     = new PoseDetector.Keypoint(0.10, 0.45, 0.05); // < 0.4 → signal 2
        kp[R_EYE]     = new PoseDetector.Keypoint(0.10, 0.55, 0.05);
        kp[L_EAR]     = new PoseDetector.Keypoint(0.10, 0.38, 0.80);
        kp[R_EAR]     = new PoseDetector.Keypoint(0.10, 0.62, 0.75);
        kp[L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, 0.80);
        kp[R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, 0.80);
        // Signal 1: faceAvg=0.05, earAvg=0.775, ratio=0.065 < 0.5 → +1 back
        // Signal 2: strongEyes=0 → +1 back
        // Signal 3: nose=0.05 < 0.3 → +1 back
        // backVotes=3 → BACK
        assertThat(detect(kp)).isEqualTo(ImageCheckService.Facing.BACK);
    }

    // ---- slightly_turned_back: signal 2 + signal 3 (2 orqa ovoz) → BACK ----
    @Test
    void slightly_turned_back_returnsBACK() {
        PoseDetector.Keypoint[] kp = blank();
        // Ko'zlar zaif + burun zaif, lekin ratio chegaradan baland
        kp[NOSE]      = new PoseDetector.Keypoint(0.10, 0.50, 0.10); // < 0.3 → signal 3
        kp[L_EYE]     = new PoseDetector.Keypoint(0.10, 0.45, 0.20); // < 0.4 → signal 2
        kp[R_EYE]     = new PoseDetector.Keypoint(0.10, 0.55, 0.15);
        kp[L_EAR]     = new PoseDetector.Keypoint(0.10, 0.38, 0.10);
        kp[R_EAR]     = new PoseDetector.Keypoint(0.10, 0.62, 0.15);
        kp[L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, 0.80);
        kp[R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, 0.80);
        // Signal 1: faceAvg=(0.10+0.20+0.15)/3=0.15, earAvg=0.125, ratio=0.15/0.125=1.2 > 0.5 → no back
        // Signal 2: strongEyes=0 → +1 back
        // Signal 3: nose=0.10 < 0.3 → +1 back
        // backVotes=2 → BACK
        assertThat(detect(kp)).isEqualTo(ImageCheckService.Facing.BACK);
    }

    // ---- left_side: kuchli yuz, chap yelka ancha kuchliroq (signal 4) → SIDE ----
    @Test
    void left_side_returnsSIDE() {
        PoseDetector.Keypoint[] kp = blank();
        kp[NOSE]      = new PoseDetector.Keypoint(0.10, 0.50, 0.90);
        kp[L_EYE]     = new PoseDetector.Keypoint(0.10, 0.45, 0.88);
        kp[R_EYE]     = new PoseDetector.Keypoint(0.10, 0.55, 0.85);
        kp[L_EAR]     = new PoseDetector.Keypoint(0.10, 0.38, 0.05);
        kp[R_EAR]     = new PoseDetector.Keypoint(0.10, 0.62, 0.08);
        kp[L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, 0.90); // kuchli
        kp[R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, 0.10); // zaif
        // Signal 1-3: backVotes=0 (yuz kuchli)
        // Signal 4: |0.90-0.10|=0.80 > 0.3 → +1 side
        // sideVotes=1, backVotes=0 → SIDE
        assertThat(detect(kp)).isEqualTo(ImageCheckService.Facing.SIDE);
    }

    // ---- right_side: kuchli yuz, o'ng yelka ancha kuchliroq (signal 4) → SIDE ----
    @Test
    void right_side_returnsSIDE() {
        PoseDetector.Keypoint[] kp = blank();
        kp[NOSE]      = new PoseDetector.Keypoint(0.10, 0.50, 0.90);
        kp[L_EYE]     = new PoseDetector.Keypoint(0.10, 0.45, 0.85);
        kp[R_EYE]     = new PoseDetector.Keypoint(0.10, 0.55, 0.88);
        kp[L_EAR]     = new PoseDetector.Keypoint(0.10, 0.38, 0.08);
        kp[R_EAR]     = new PoseDetector.Keypoint(0.10, 0.62, 0.05);
        kp[L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, 0.10); // zaif
        kp[R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, 0.90); // kuchli
        // Signal 1-3: backVotes=0 (yuz kuchli)
        // Signal 4: |0.10-0.90|=0.80 > 0.3 → +1 side
        // sideVotes=1, backVotes=0 → SIDE
        assertThat(detect(kp)).isEqualTo(ImageCheckService.Facing.SIDE);
    }

    // ---- side_with_back_signals: yon belgi bor lekin 2+ orqa ovoz → BACK ustunlik qiladi ----
    @Test
    void side_signal_overridden_by_two_back_votes_returnsBACK() {
        PoseDetector.Keypoint[] kp = blank();
        kp[NOSE]      = new PoseDetector.Keypoint(0.10, 0.50, 0.05); // < 0.3 → signal 3
        kp[L_EYE]     = new PoseDetector.Keypoint(0.10, 0.45, 0.05); // < 0.4 → signal 2
        kp[R_EYE]     = new PoseDetector.Keypoint(0.10, 0.55, 0.05);
        kp[L_EAR]     = new PoseDetector.Keypoint(0.10, 0.38, 0.05);
        kp[R_EAR]     = new PoseDetector.Keypoint(0.10, 0.62, 0.05);
        kp[L_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.40, 0.90); // asimmetrik
        kp[R_SHOULDER] = new PoseDetector.Keypoint(0.30, 0.60, 0.10);
        // Signal 2+3: backVotes=2 → BACK (yon signali e'tiborga olinmaydi)
        assertThat(detect(kp)).isEqualTo(ImageCheckService.Facing.BACK);
    }
}
