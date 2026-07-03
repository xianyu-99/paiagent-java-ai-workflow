package com.paiagent.common.security;

import org.springframework.util.StringUtils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class SafeUrlValidator {

    private static final Set<String> METADATA_HOSTS = Set.of(
            "169.254.169.254",
            "100.100.100.200"
    );

    private SafeUrlValidator() {
    }

    public static URI requireSafeHttpUri(String rawUrl, String fieldName, boolean allowPrivateNetworkUrls) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(fieldName + " is not a valid URL", e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException(fieldName + " must use http or https");
        }

        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(fieldName + " must not contain user info");
        }

        String host = uri.getHost();
        if (!StringUtils.hasText(host)) {
            throw new IllegalArgumentException(fieldName + " must contain a host");
        }

        if (!allowPrivateNetworkUrls) {
            rejectPrivateOrMetadataHost(host, fieldName);
        }

        return uri;
    }

    private static void rejectPrivateOrMetadataHost(String host, String fieldName) {
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(normalizedHost) || "metadata.google.internal".equals(normalizedHost)) {
            throw new IllegalArgumentException(fieldName + " points to a metadata service");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new IllegalArgumentException(fieldName + " host cannot be resolved", e);
        }

        for (InetAddress address : addresses) {
            if (isUnsafeAddress(address)) {
                throw new IllegalArgumentException(fieldName + " points to a private or local network address");
            }
        }
    }

    private static boolean isUnsafeAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first == 0
                    || first == 10
                    || first == 127
                    || (first == 100 && second >= 64 && second <= 127)
                    || (first == 169 && second == 254)
                    || (first == 172 && second >= 16 && second <= 31)
                    || (first == 192 && second == 168)
                    || (first == 198 && (second == 18 || second == 19));
        }

        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            return first == 0xfc || first == 0xfd || first == 0xfe;
        }

        return false;
    }
}
