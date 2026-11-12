package com.yigongbao.backup.backup;

import com.yigongbao.backup.remote.RemoteHttpSupport;
import com.yigongbao.backup.cleanup.CleanupClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;

/** 以流式方式从受控 COS 代理下载对象，避免把 1-2GB 订单文件载入内存。 */
public final class HttpCosObjectClient implements CosObjectClient {
    private final RemoteHttpSupport http;

    public HttpCosObjectClient(RemoteHttpSupport http) {
        this.http = http;
    }

    @Override
    public InputStream download(String sourceKey) throws IOException {
        if (sourceKey == null || sourceKey.isBlank()) {
            throw new IllegalArgumentException("COS对象下载地址不能为空");
        }
        // Manifest 的 sourceObject.url 是线上签发的短期只读地址；YAML 未定义按 objectKey
        // 代理下载的本地路径，因此不能再拼接不存在的 /api/v1/backup/cos/objects 接口。
        URI target;
        try {
            target = URI.create(sourceKey);
        } catch (IllegalArgumentException ex) {
            throw new IOException("COS对象下载地址非法", ex);
        }
        HttpResponse<InputStream> response = http.download(target);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            http.ensureSuccess(response);
        }
        return response.body();
    }

    @Override
    public void delete(String sourceKey, CleanupClient.Permit permit) throws IOException {
        throw new IOException("拒绝未绑定精确版本的 COS 删除");
    }

    @Override
    public void delete(String sourceKey, String versionIdentity, CleanupClient.Permit permit) throws IOException {
        if (sourceKey == null || sourceKey.isBlank() || permit == null
                || permit.permitId() == null || permit.permitId().isBlank()
                || !"ACTIVE".equals(permit.status())
                || versionIdentity == null || versionIdentity.isBlank()
                || permit.objects().stream().noneMatch(item -> sourceKey.equals(item.objectKey())
                    && versionIdentity.equals(item.versionIdentity()))) {
            throw new IOException("COS对象删除缺少匹配对象键及版本的ACTIVE permit");
        }
        URI target;
        try {
            target = URI.create(sourceKey);
        } catch (IllegalArgumentException ex) {
            throw new IOException("COS对象删除地址非法", ex);
        }
        String query = target.getRawQuery();
        if (query == null || java.util.Arrays.stream(query.split("&"))
                .map(part -> part.split("=", 2))
                .noneMatch(pair -> pair.length == 2 && ("versionId".equalsIgnoreCase(pair[0])
                        || "versionIdentity".equalsIgnoreCase(pair[0]))
                        && java.net.URLDecoder.decode(pair[1], java.nio.charset.StandardCharsets.UTF_8).equals(versionIdentity)))
            throw new IOException("COS 删除地址未绑定 permit 指定的精确版本");
        http.ensureSuccess(http.delete(target));
    }
}
