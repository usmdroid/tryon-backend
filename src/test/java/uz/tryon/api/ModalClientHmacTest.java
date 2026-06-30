package uz.tryon.api;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ModalClient HMAC signing and ModalSecretValidator startup check.
 * No Spring context — pure unit tests using Mockito for HttpClient.
 */
class ModalClientHmacTest {

    // ── HMAC regression ──────────────────────────────────────────────────────

    @Test
    void hmacSha256Hex_knownFixture() {
        // Fixture verified independently:
        // python3 -c "import hmac,hashlib; print(hmac.new(b'topsecretkey', b'1700000000.{\"a\":1}', hashlib.sha256).hexdigest())"
        // → c4d8ba37085a82885baa6b14c36d341d2ca18e8193d21bc1079dd3616fb547c1
        String result = ModalClient.hmacSha256Hex("topsecretkey", "1700000000.{\"a\":1}");
        assertEquals("c4d8ba37085a82885baa6b14c36d341d2ca18e8193d21bc1079dd3616fb547c1", result);
    }

    // ── Mock Modal server responses ───────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void modal200_returnsOkResult() throws Exception {
        byte[] fakeWebp = {0x52, 0x49, 0x46, 0x46};  // RIFF magic bytes
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.body()).thenReturn(fakeWebp);
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        ModalClient client = new ModalClient(testConfig(), mockHttp);
        ModalClient.Result result = client.generate("aA==", "bB==", "upper");

        assertTrue(result.ok());
        assertArrayEquals(fakeWebp, result.image());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void modal403_returnsModalAuthError() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        HttpResponse mockResp = mock(HttpResponse.class);
        when(mockResp.statusCode()).thenReturn(403);
        when(mockResp.body()).thenReturn("Invalid signature".getBytes());
        doReturn(mockResp).when(mockHttp).send(any(HttpRequest.class), any());

        ModalClient client = new ModalClient(testConfig(), mockHttp);
        ModalClient.Result result = client.generate("aA==", "bB==", "upper");

        assertFalse(result.ok());
        assertTrue(result.error().contains("Modal auth"));
    }

    @Test
    void modalConnectionFailed_returnsAloqaUzildi() throws Exception {
        HttpClient mockHttp = mock(HttpClient.class);
        doThrow(new IOException("Connection refused"))
                .when(mockHttp).send(any(HttpRequest.class), any());

        ModalClient client = new ModalClient(testConfig(), mockHttp);
        ModalClient.Result result = client.generate("aA==", "bB==", "upper");

        assertFalse(result.ok());
        assertTrue(result.error().contains("aloqa uzildi"));
    }

    // ── ModalSecretValidator ──────────────────────────────────────────────────

    @Test
    void blankSecret_throwsOnValidate() {
        AppConfig config = new AppConfig();
        config.setModalSecret("");
        assertThrows(IllegalStateException.class, new ModalSecretValidator(config)::validate);
    }

    @Test
    void nullSecret_throwsOnValidate() {
        AppConfig config = new AppConfig();
        config.setModalSecret(null);
        assertThrows(IllegalStateException.class, new ModalSecretValidator(config)::validate);
    }

    @Test
    void defaultSentinel_throwsOnValidate() {
        AppConfig config = new AppConfig();
        config.setModalSecret("dev-secret-change-me");
        assertThrows(IllegalStateException.class, new ModalSecretValidator(config)::validate);
    }

    @Test
    void realSecret_validatesOk() {
        AppConfig config = new AppConfig();
        config.setModalSecret("a-real-secret-value-abcdefghij1234567890");
        assertDoesNotThrow(new ModalSecretValidator(config)::validate);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private AppConfig testConfig() {
        AppConfig config = new AppConfig();
        config.setModalUrl("http://localhost:9999/test");
        config.setModalSecret("test-secret-for-unit-tests-abcdef");
        return config;
    }
}
