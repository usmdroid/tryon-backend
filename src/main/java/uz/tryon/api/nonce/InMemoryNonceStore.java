package uz.tryon.api.nonce;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Xotiradagi nonce saqlash — bitta server, lokal/dev/test uchun. */
public class InMemoryNonceStore implements NonceStore {

    private final Map<String, Long> used = new ConcurrentHashMap<>();

    @Override
    public boolean tryConsume(String nonce, long expEpochMs) {
        long now = System.currentTimeMillis();
        if (used.size() > 10_000) {
            used.entrySet().removeIf(e -> e.getValue() < now);
        }
        return used.putIfAbsent(nonce, expEpochMs) == null;
    }
}
