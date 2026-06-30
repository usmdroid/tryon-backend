package uz.tryon.api.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

/** Static crypto helpers: HMAC-SHA256, SHA-256, base64url, hex, constant-time compare. */
public final class AuthHashUtils {

    private AuthHashUtils() {}

    /** HMAC-SHA256 of {@code message} keyed with {@code secret}, returned as raw bytes. */
    public static byte[] hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC xatosi", e);
        }
    }

    /** HMAC-SHA256 of {@code message} keyed with {@code secret}, returned as lowercase hex. */
    public static String hmacSha256Hex(String secret, String message) {
        return HexFormat.of().formatHex(hmacSha256(secret, message));
    }

    /** SHA-256 digest of {@code s}. */
    public static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Base64url-encodes {@code bytes} without padding. */
    public static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase hex encoding of {@code bytes}. */
    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * Constant-time string equality — prevents timing attacks on signature comparison.
     * Uses {@link MessageDigest#isEqual} which runs in fixed time regardless of where strings differ.
     */
    public static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
