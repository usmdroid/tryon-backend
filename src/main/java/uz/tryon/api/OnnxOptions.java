package uz.tryon.api;

import ai.onnxruntime.OrtSession;

/** ONNX Runtime'ni kam xotira/CPU bilan ishlatish sozlamalari (Railway kabi cheklangan muhit uchun). */
public final class OnnxOptions {
    private OnnxOptions() { }

    public static OrtSession.SessionOptions lowMemory() throws Exception {
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(1);
        o.setInterOpNumThreads(1);
        o.setMemoryPatternOptimization(false);
        return o;
    }
}
