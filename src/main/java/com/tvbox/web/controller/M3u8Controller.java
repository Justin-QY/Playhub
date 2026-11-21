package com.tvbox.web.controller;

import com.tvbox.web.service.HttpClientFactory;
import com.tvbox.web.service.M3u8Purifier;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/**
 * M3U8 净化接口。
 *
 * 前端在播放 m3u8 时把地址换成 /api/m3u8/purify?url=...，
 * 服务端拉取播放列表、清理广告分片，再把分片地址改写为绝对地址返回。
 * 真正的视频分片仍由浏览器直连源站，不消耗服务器带宽。
 */
@RestController
@RequestMapping("/api/m3u8")
public class M3u8Controller {

    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 4;
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; TVBox) AppleWebKit/537.36 Chrome/120 Safari/537.36";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .sslContext(HttpClientFactory.sslContext())
            .build();

    @GetMapping("/purify")
    public ResponseEntity<byte[]> purify(@RequestParam("url") String url,
                                         @RequestParam(value = "r", required = false) String referer,
                                         HttpServletRequest request) {
        if (url == null || url.isBlank() || url.length() > 8192) {
            return ResponseEntity.badRequest().body("bad url".getBytes(StandardCharsets.UTF_8));
        }

        FetchResult fetched;
        try {
            fetched = fetch(url, referer);
        } catch (Exception e) {
            return redirectTo(url);
        }
        if (fetched == null || fetched.body == null) {
            return redirectTo(url);
        }

        String content = new String(fetched.body, StandardCharsets.UTF_8);
        if (content.startsWith("\ufeff")) {
            content = content.substring(1);
        }
        if (!content.startsWith("#EXTM3U")) {
            // 不是播放列表（可能是校验页/直链），交回浏览器自己处理
            return redirectTo(url);
        }

        String prefix = endpointPrefix(request, referer);
        String result;
        if (M3u8Purifier.isMaster(content)) {
            result = M3u8Purifier.rewriteMaster(fetched.finalUrl, content, prefix);
        } else {
            M3u8Purifier.Result purified = M3u8Purifier.purify(fetched.finalUrl, content);
            result = purified.content();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"));
        headers.setCacheControl("no-store");
        headers.add("Access-Control-Allow-Origin", "*");
        headers.add("X-M3u8-Purified", "1");
        return new ResponseEntity<>(result.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    private String endpointPrefix(HttpServletRequest request, String referer) {
        if (referer != null && !referer.isBlank()) {
            String enc = java.net.URLEncoder.encode(referer, StandardCharsets.UTF_8);
            return "/api/m3u8/purify?r=" + enc + "&url=";
        }
        return "/api/m3u8/purify?url=";
    }

    private ResponseEntity<byte[]> redirectTo(String url) {
        try {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("bad url".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class FetchResult {
        final String finalUrl;
        final byte[] body;

        FetchResult(String finalUrl, byte[] body) {
            this.finalUrl = finalUrl;
            this.body = body;
        }
    }

    private FetchResult fetch(String url, String referer) throws Exception {
        String current = url;
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            if (!isSafeUrl(current)) {
                throw new IllegalArgumentException("unsafe url");
            }
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(current))
                    .timeout(Duration.ofSeconds(12))
                    .header("User-Agent", UA)
                    .header("Accept", "*/*")
                    .GET();
            if (referer != null && !referer.isBlank()) {
                builder.header("Referer", referer);
            }

            HttpResponse<InputStream> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            int code = resp.statusCode();

            if (code >= 300 && code < 400) {
                String location = resp.headers().firstValue("location").orElse("");
                resp.body().close();
                if (location.isBlank()) {
                    throw new IllegalStateException("redirect without location");
                }
                current = URI.create(current).resolve(location).toString();
                continue;
            }
            if (code != 200) {
                resp.body().close();
                throw new IllegalStateException("http " + code);
            }

            try (InputStream in = resp.body()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IllegalStateException("playlist too large");
                    }
                    out.write(buf, 0, n);
                }
                return new FetchResult(current, out.toByteArray());
            }
        }
        throw new IllegalStateException("too many redirects");
    }

    /** 只允许公网 http/https，避免 SSRF */
    static boolean isSafeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            for (InetAddress addr : InetAddress.getAllByName(host)) {
                if (addr.isAnyLocalAddress() || addr.isLoopbackAddress() || addr.isLinkLocalAddress()
                        || addr.isSiteLocalAddress() || addr.isMulticastAddress()) {
                    return false;
                }
                byte[] raw = addr.getAddress();
                if (raw.length == 4) {
                    int b0 = raw[0] & 0xff;
                    int b1 = raw[1] & 0xff;
                    if (b0 == 10 || b0 == 127 || (b0 == 169 && b1 == 254)
                            || (b0 == 172 && b1 >= 16 && b1 <= 31)
                            || (b0 == 192 && b1 == 168)
                            || (b0 == 100 && b1 >= 64 && b1 <= 127)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
