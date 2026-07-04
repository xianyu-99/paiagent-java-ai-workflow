package com.paiagent.common.security;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SafeUrlValidatorTest {

    @Test
    void shouldAllowPublicHttpUrl() {
        URI uri = assertDoesNotThrow(() ->
                SafeUrlValidator.requireSafeHttpUri("https://8.8.8.8/v1/images", "test url", false));

        assertEquals("https", uri.getScheme());
        assertEquals("8.8.8.8", uri.getHost());
    }

    @Test
    void shouldRejectUnsupportedScheme() {
        assertThrows(IllegalArgumentException.class, () ->
                SafeUrlValidator.requireSafeHttpUri("file:///etc/passwd", "test url", false));
    }

    @Test
    void shouldRejectLoopbackHostByDefault() {
        assertThrows(IllegalArgumentException.class, () ->
                SafeUrlValidator.requireSafeHttpUri("http://127.0.0.1:8080/admin", "test url", false));
    }

    @Test
    void shouldRejectLocalhostByDefault() {
        assertThrows(IllegalArgumentException.class, () ->
                SafeUrlValidator.requireSafeHttpUri("http://localhost:8080/admin", "test url", false));
    }

    @Test
    void shouldRejectPrivateNetworkHostByDefault() {
        assertThrows(IllegalArgumentException.class, () ->
                SafeUrlValidator.requireSafeHttpUri("http://10.0.0.8/internal", "test url", false));
    }

    @Test
    void shouldRejectMetadataServiceByDefault() {
        assertThrows(IllegalArgumentException.class, () ->
                SafeUrlValidator.requireSafeHttpUri("http://169.254.169.254/latest/meta-data", "test url", false));
    }

    @Test
    void shouldRejectMetadataServiceEvenWhenPrivateNetworkIsEnabled() {
        assertThrows(IllegalArgumentException.class, () ->
                SafeUrlValidator.requireSafeHttpUri("http://169.254.169.254/latest/meta-data", "test url", true));
    }

    @Test
    void shouldAllowPrivateNetworkWhenExplicitlyEnabled() {
        URI uri = assertDoesNotThrow(() ->
                SafeUrlValidator.requireSafeHttpUri("http://127.0.0.1:11434/v1", "test url", true));

        assertEquals("127.0.0.1", uri.getHost());
    }
}
