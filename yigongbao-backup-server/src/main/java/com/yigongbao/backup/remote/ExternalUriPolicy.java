package com.yigongbao.backup.remote;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** 对对象存储预签名地址执行主机白名单和非公网地址校验。 */
public final class ExternalUriPolicy {
    private final Set<String> allowedHosts;
    private final HostResolver resolver;
    private final boolean testMode;

    public ExternalUriPolicy(Set<String> allowedHosts, HostResolver resolver, boolean testMode) {
        TreeSet<String> normalized = new TreeSet<>();
        if (allowedHosts != null) {
            for (String host : allowedHosts) {
                if (host != null && !host.isBlank()) normalized.add(normalizePattern(host));
            }
        }
        this.allowedHosts = Set.copyOf(normalized);
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
        this.testMode = testMode;
    }

    public static ExternalUriPolicy production(Set<String> allowedHosts) {
        return new ExternalUriPolicy(allowedHosts, InetAddress::getAllByName, false);
    }

    /** 仅供显式构造的测试/兼容客户端；生产 Spring 装配始终使用 production。 */
    static ExternalUriPolicy testCompatibility() {
        return new ExternalUriPolicy(Set.of("*"), host -> new InetAddress[0], true);
    }

    public void validate(URI uri, String message) {
        if (uri == null || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(message);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("https".equals(scheme) || testMode && "http".equals(scheme))) {
            throw new IllegalArgumentException(message + "：仅允许 HTTPS");
        }
        int port = uri.getPort();
        if (!testMode && port >= 0 && port != 443) {
            throw new IllegalArgumentException(message + "：端口不在允许范围");
        }
        String host = normalizeHost(uri.getHost());
        if (!allowed(host)) {
            throw new IllegalArgumentException(message + "：主机 " + host
                    + " 不在 BACKUP_EXTERNAL_ALLOWED_HOSTS 允许列表中");
        }
        if (testMode) return;
        try {
            InetAddress[] addresses = resolver.resolve(host);
            if (addresses == null || addresses.length == 0) throw new UnknownHostException(host);
            for (InetAddress address : addresses) {
                if (!isPublic(address)) throw new IllegalArgumentException(message + "：目标解析到内网或保留地址");
            }
        } catch (UnknownHostException failure) {
            throw new IllegalArgumentException(message + "：主机无法解析", failure);
        }
    }

    private boolean allowed(String host) {
        for (String pattern : allowedHosts) {
            if ("*".equals(pattern) || pattern.equals(host)) return true;
            if (pattern.startsWith("*.") && host.endsWith(pattern.substring(1))
                    && host.length() > pattern.length() - 1) return true;
        }
        return false;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return first != 0 && first != 127 && first < 224
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 169 && second == 254);
        }
        // IPv6 unique-local fc00::/7 不是所有 JDK 都会归类为 site-local。
        return bytes.length != 16 || (bytes[0] & 0xfe) != 0xfc;
    }

    private static String normalizePattern(String value) {
        String pattern = value.trim().toLowerCase(Locale.ROOT);
        if ("*".equals(pattern)) return pattern;
        if (pattern.startsWith("*.")) return "*." + normalizeHost(pattern.substring(2));
        return normalizeHost(pattern);
    }

    private static String normalizeHost(String value) {
        String host = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
        return IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
