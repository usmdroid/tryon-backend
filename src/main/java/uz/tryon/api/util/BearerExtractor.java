package uz.tryon.api.util;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Optional;

/** Extracts the raw Bearer token from an Authorization header. */
public final class BearerExtractor {

    private BearerExtractor() {}

    /**
     * Returns the token string from {@code Authorization: Bearer <token>},
     * or empty if the header is absent or malformed.
     */
    public static Optional<String> extract(HttpServletRequest request) {
        return extract(request.getHeader("Authorization"));
    }

    /** Same extraction from a raw header string (useful in tests). */
    public static Optional<String> extract(String header) {
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        return Optional.of(header.substring(7));
    }
}
