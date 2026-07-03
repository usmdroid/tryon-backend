package uz.tryon.api.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for TryOnEventService static helper methods:
 * maskIp and shortDeviceId — validates IP masking and device ID truncation logic.
 */
class TryOnEventServiceTest {

    // ── maskIp ────────────────────────────────────────────────────────────────

    @Test
    void maskIp_typicalIPv4_masksLastTwoOctets() {
        assertEquals("84.54.*.*", TryOnEventService.maskIp("84.54.123.45"));
    }

    @Test
    void maskIp_anotherIPv4_masksLastTwoOctets() {
        assertEquals("192.168.*.*", TryOnEventService.maskIp("192.168.1.100"));
    }

    @Test
    void maskIp_null_returnsNull() {
        assertNull(TryOnEventService.maskIp(null));
    }

    @Test
    void maskIp_blank_returnsNull() {
        assertNull(TryOnEventService.maskIp(""));
    }

    @Test
    void maskIp_ipv6_masksSecondHalf() {
        String result = TryOnEventService.maskIp("2001:db8:85a3:0000:0000:8a2e:0370:7334");
        assertTrue(result.endsWith(":****"), "IPv6 must end with :****; got: " + result);
        assertFalse(result.contains("7334"), "Full IPv6 must not appear in masked result");
    }

    @Test
    void maskIp_shortIPv4_doesNotCrash() {
        // Edge: "1.2.3.4" → "1.2.*.*"
        assertEquals("1.2.*.*", TryOnEventService.maskIp("1.2.3.4"));
    }

    // ── shortDeviceId ─────────────────────────────────────────────────────────

    @Test
    void shortDeviceId_longId_returnsEllipsisPlusLast6() {
        String result = TryOnEventService.shortDeviceId("abc123def456");
        assertEquals("…def456", result);
    }

    @Test
    void shortDeviceId_exactly6chars_returnsAsIs() {
        assertEquals("abc123", TryOnEventService.shortDeviceId("abc123"));
    }

    @Test
    void shortDeviceId_lessThan6chars_returnsAsIs() {
        assertEquals("abc", TryOnEventService.shortDeviceId("abc"));
    }

    @Test
    void shortDeviceId_null_returnsNull() {
        assertNull(TryOnEventService.shortDeviceId(null));
    }

    @Test
    void shortDeviceId_blank_returnsNull() {
        assertNull(TryOnEventService.shortDeviceId(""));
    }

    @Test
    void shortDeviceId_neverExposesFullId() {
        String longId = "device-id-abcdef123456";
        String result = TryOnEventService.shortDeviceId(longId);
        assertNotNull(result);
        assertNotEquals(longId, result, "Full device ID must not be exposed");
        assertTrue(result.startsWith("…"), "Truncated ID must start with ellipsis");
    }
}
