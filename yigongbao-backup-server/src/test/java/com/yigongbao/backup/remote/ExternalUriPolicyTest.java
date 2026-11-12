package com.yigongbao.backup.remote;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalUriPolicyTest {
    @Test
    void acceptsOnlyHttpsHostsOnTheConfiguredAllowlist() throws Exception {
        ExternalUriPolicy policy = new ExternalUriPolicy(Set.of("cos.example.com"),
                host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})}, false);

        assertThatCode(() -> policy.validate(URI.create("https://cos.example.com/object?signature=x"), "bad"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.validate(URI.create("https://evil.example.com/object"), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.validate(URI.create("http://cos.example.com/object"), "bad"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAllowedHostWhenDnsResolvesToPrivateOrLoopbackAddress() throws Exception {
        ExternalUriPolicy privateDns = new ExternalUriPolicy(Set.of("cos.example.com"),
                host -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 7})}, false);

        assertThatThrownBy(() -> privateDns.validate(URI.create("https://cos.example.com/object"), "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }

    @Test
    void reportsRejectedHostAndRequiredConfigurationWithoutLeakingTheUrl() {
        ExternalUriPolicy policy = new ExternalUriPolicy(Set.of("cos.example.com"),
                host -> new InetAddress[0], false);

        assertThatThrownBy(() -> policy.validate(
                URI.create("https://unexpected.example.com/private/object?signature=secret"), "COS下载地址非法"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unexpected.example.com")
                .hasMessageContaining("BACKUP_EXTERNAL_ALLOWED_HOSTS")
                .hasMessageNotContaining("private/object")
                .hasMessageNotContaining("secret");
    }
}
