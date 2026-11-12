/** 文件功能：通过 Kubo HTTP API 实现 IPFS add、cat 和 pin。 */
package lab.blockchain.backend.adapter;

import lab.blockchain.backend.port.ObjectStoragePort;
import lab.blockchain.core.Hashing;
import lab.blockchain.core.StoredObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IPFS 适配器只负责对象操作。
 * 它不懂溯源阶段，也不直接调用 Fabric；业务编排由 TraceApplicationService 完成。
 */
public final class IpfsHttpObjectStorage implements ObjectStoragePort {
    private static final Pattern HASH_FIELD = Pattern.compile("\\\"Hash\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    private final HttpClient client;
    private final String apiBase;

    public IpfsHttpObjectStorage(String apiBase) {
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.apiBase = trimTrailingSlash(apiBase);
    }

    @Override
    public StoredObject add(byte[] bytes) throws IOException {
        String boundary = "----blockchain-storage-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"upload.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + bytes.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(bytes, 0, body, prefix.length, bytes.length);
        System.arraycopy(suffix, 0, body, prefix.length + bytes.length, suffix.length);

        HttpRequest request = HttpRequest.newBuilder(uri("/api/v0/add?pin=false"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
        Matcher matcher = HASH_FIELD.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("IPFS add response has no Hash field: " + response.body());
        }
        String cid = matcher.group(1);
        return new StoredObject(cid, Hashing.sha256Hex(bytes), bytes.length);
    }

    @Override
    public byte[] cat(String cid) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v0/cat?arg=" + encode(cid)))
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return send(request, HttpResponse.BodyHandlers.ofByteArray()).body();
    }

    @Override
    public void pin(String cid) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v0/pin/add?arg=" + encode(cid)))
                .timeout(Duration.ofMinutes(2))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        send(request, HttpResponse.BodyHandlers.ofString());
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        try {
            HttpResponse<T> response = client.send(request, handler);
            if (response.statusCode() / 100 != 2) {
                throw new IOException("IPFS HTTP " + response.statusCode() + ": " + response.body());
            }
            return response;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("IPFS request interrupted", e);
        }
    }

    private URI uri(String path) {
        return URI.create(apiBase + path);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
