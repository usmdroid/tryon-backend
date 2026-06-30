package uz.tryon.api.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AuthHashUtils, BearerExtractor, and ApiErrors.
 */
class UtilsTest {

    // ── AuthHashUtils ─────────────────────────────────────────────────────────

    @Test
    void hmacSha256_sameAsMd5alClientFixture() {
        // Same fixture as ModalClientHmacTest — verifies shared implementation is identical.
        String result = AuthHashUtils.hmacSha256Hex("topsecretkey", "1700000000.{\"a\":1}");
        assertEquals("c4d8ba37085a82885baa6b14c36d341d2ca18e8193d21bc1079dd3616fb547c1", result);
    }

    @Test
    void hmacSha256_returnsBytes_sameAsHex() {
        byte[] bytes = AuthHashUtils.hmacSha256("key", "msg");
        String hex = AuthHashUtils.hmacSha256Hex("key", "msg");
        assertEquals(hex, AuthHashUtils.hex(bytes));
    }

    @Test
    void sha256_knownHash() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String empty = AuthHashUtils.hex(AuthHashUtils.sha256(""));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb924" +
                "27ae41e4649b934ca495991b7852b855", empty);
    }

    @Test
    void b64Url_noPaddingAndUrlSafe() {
        String result = AuthHashUtils.b64Url(new byte[]{(byte) 0xFF, (byte) 0xFE});
        assertFalse(result.contains("="), "must not have padding");
        assertFalse(result.contains("+"), "must use URL-safe alphabet");
        assertFalse(result.contains("/"), "must use URL-safe alphabet");
    }

    @Test
    void constantTimeEquals_equalStrings_true() {
        assertTrue(AuthHashUtils.constantTimeEquals("abc", "abc"));
    }

    @Test
    void constantTimeEquals_differentStrings_false() {
        assertFalse(AuthHashUtils.constantTimeEquals("abc", "abd"));
    }

    @Test
    void constantTimeEquals_differentLengths_false() {
        assertFalse(AuthHashUtils.constantTimeEquals("abc", "abcd"));
    }

    @Test
    void hex_lowercaseOutput() {
        String h = AuthHashUtils.hex(new byte[]{(byte) 0xAB, (byte) 0xCD});
        assertEquals("abcd", h);
    }

    // ── BearerExtractor ───────────────────────────────────────────────────────

    @Test
    void extract_validBearer_returnsToken() {
        Optional<String> result = BearerExtractor.extract("Bearer mytoken123");
        assertTrue(result.isPresent());
        assertEquals("mytoken123", result.get());
    }

    @Test
    void extract_nullHeader_empty() {
        assertTrue(BearerExtractor.extract((String) null).isEmpty());
    }

    @Test
    void extract_emptyHeader_empty() {
        assertTrue(BearerExtractor.extract("").isEmpty());
    }

    @Test
    void extract_noBearer_empty() {
        assertTrue(BearerExtractor.extract("Basic dXNlcjpwYXNz").isEmpty());
    }

    @Test
    void extract_bearerOnly_emptyToken() {
        Optional<String> result = BearerExtractor.extract("Bearer ");
        assertTrue(result.isPresent());
        assertEquals("", result.get());
    }

    @Test
    void extract_request_delegatesToHeader() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn("Bearer tok");
        assertEquals(Optional.of("tok"), BearerExtractor.extract(req));
    }

    @Test
    void extract_request_missingHeader_empty() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("Authorization")).thenReturn(null);
        assertTrue(BearerExtractor.extract(req).isEmpty());
    }

    // ── ApiErrors ─────────────────────────────────────────────────────────────

    @Test
    void err_statusAndBody() {
        ResponseEntity<Map<String, String>> resp = ApiErrors.err(HttpStatus.BAD_REQUEST, "xato");
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("xato", resp.getBody().get("error"));
    }

    @Test
    void err_404_correctStatus() {
        ResponseEntity<Map<String, String>> resp = ApiErrors.err(HttpStatus.NOT_FOUND, "topilmadi");
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void err_contentTypeJson() {
        ResponseEntity<Map<String, String>> resp = ApiErrors.err(HttpStatus.UNAUTHORIZED, "401");
        assertNotNull(resp.getHeaders().getContentType());
        assertTrue(resp.getHeaders().getContentType().toString().contains("application/json"));
    }
}
