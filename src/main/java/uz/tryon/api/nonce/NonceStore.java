package uz.tryon.api.nonce;

/**
 * Bir martali nonce saqlash interfeysi.
 * true — nonce birinchi marta ishlatildi (muvaffaqiyatli).
 * false — nonce allaqachon ishlatilgan (rad etilsin).
 */
public interface NonceStore {
    boolean tryConsume(String nonce, long expEpochMs);
}
