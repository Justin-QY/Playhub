package com.tvbox.web.service;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M3U8 净化：去掉播放列表里的广告分片、广告标记和异常分片组。
 *
 * 逻辑参考 AVBox-QY 的 M3u8.purify（Android 端），这里是服务端 Java 版本：
 * - 只处理 media playlist（master playlist 由控制器改写子播放列表地址）
 * - 所有分片地址改写为绝对地址，浏览器可直接拉流，不经过服务器
 * - 任何异常或过度删除都会回退到原始内容
 */
public final class M3u8Purifier {

    public record Result(String content, int removed, boolean changed) {}

    private static final String TAG_EXTM3U = "#EXTM3U";
    private static final String TAG_INF = "#EXTINF";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
    private static final String TAG_KEY = "#EXT-X-KEY";
    private static final String TAG_MAP = "#EXT-X-MAP";
    private static final String TAG_CUE_OUT = "#EXT-X-CUE-OUT";
    private static final String TAG_CUE_IN = "#EXT-X-CUE-IN";
    private static final String TAG_DATERANGE = "#EXT-X-DATERANGE";
    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";

    private static final Pattern AD_SEGMENT = Pattern.compile(
            "(?i)(^|[/?&=_.-])(ads?|adv|advert(ise(ment)?)?|commercial|preroll|pre-roll|midroll|mid-roll|postroll|post-roll|sponsor|scte|vast|vmap|interstitial|bumper)([/?&=_.-]|$)");
    private static final Pattern URI_ATTR = Pattern.compile("URI=\"(.+?)\"");
    private static final Pattern EXTINF_VALUE = Pattern.compile("#EXTINF:([0-9.]+)");

    private static final String[] AD_DOMAINS = {
            "adservice", "adserver", "adsystem", "doubleclick", "googlesyndication",
            "advertising", "2mdn.net", "moatads", "scorecardresearch", "quantserve"
    };

    private M3u8Purifier() {
    }

    public static Result purify(String baseUrl, String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Result(raw, 0, false);
        }
        String content = raw;
        if (content.startsWith("\ufeff")) {
            content = content.substring(1);
        }
        content = content.replace("\r\n", "\n").replace('\r', '\n');
        if (!content.startsWith(TAG_EXTM3U)) {
            return new Result(raw, 0, false);
        }

        int totalSegments = countSegments(content);
        if (totalSegments == 0) {
            return new Result(resolveAll(baseUrl, content), 0, false);
        }

        try {
            Counter counter = new Counter();
            String working = cleanCommonMarkers(content, counter);

            String minority = removeMinorityUrl(baseUrl, working, counter);
            if (minority != null) {
                working = minority;
            }

            working = cleanDiscontinuityGroups(working, counter);

            // 删除过多就不敢用了
            if (counter.value > totalSegments * 0.5) {
                return new Result(resolveAll(baseUrl, content), 0, false);
            }
            if (counter.value > 0 && !isPlayable(working)) {
                return new Result(resolveAll(baseUrl, content), 0, false);
            }

            working = keepEndList(content, working);
            working = resolveAll(baseUrl, working);
            working = normalize(working);

            return new Result(working, counter.value, counter.value > 0);
        } catch (Exception e) {
            return new Result(resolveAll(baseUrl, content), 0, false);
        }
    }

    /** master playlist：把每个子播放列表地址改写成 purify 接口地址 */
    public static String rewriteMaster(String playlistUrl, String content, String endpointPrefix) {
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\ufeff")) {
            normalized = normalized.substring(1);
        }
        StringBuilder sb = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            String item = line.trim();
            if (item.isEmpty() || item.startsWith("#")) {
                // EXT-X-MEDIA 的 URI 属性指向子播放列表，也要改写
                if (item.startsWith("#EXT-X-MEDIA")) {
                    sb.append(rewriteUriAttribute(playlistUrl, line, endpointPrefix)).append('\n');
                } else {
                    sb.append(line).append('\n');
                }
                continue;
            }
            String absolute = resolve(playlistUrl, item);
            sb.append(endpointPrefix).append(encode(absolute)).append('\n');
        }
        return sb.toString();
    }

    private static String rewriteUriAttribute(String baseUrl, String line, String endpointPrefix) {
        Matcher m = URI_ATTR.matcher(line);
        if (!m.find()) {
            return line;
        }
        String value = resolve(baseUrl, m.group(1));
        return line.replace(m.group(1), endpointPrefix + encode(value));
    }

    private static String encode(String url) {
        return java.net.URLEncoder.encode(url, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static int countSegments(String content) {
        int n = 0;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                n++;
            }
        }
        return n;
    }

    private static final class Counter {
        int value;
    }

    /** 清理 CUE / SCTE / DATERANGE / 广告域名等通用广告标记 */
    private static String cleanCommonMarkers(String content, Counter counter) {
        List<String> out = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        boolean inAdBreak = false;
        boolean changed = false;

        for (String raw : content.split("\n", -1)) {
            String item = raw.trim();
            if (item.isEmpty()) {
                if (pending.isEmpty()) {
                    out.add(raw);
                } else {
                    pending.add(raw);
                }
                continue;
            }

            if (item.startsWith("#")) {
                if (item.startsWith(TAG_CUE_IN)) {
                    if (inAdBreak || hasAdSignal(pending)) {
                        inAdBreak = false;
                        pending.clear();
                        changed = true;
                        continue;
                    }
                }
                if (item.startsWith(TAG_CUE_OUT)) {
                    flush(out, pending);
                    inAdBreak = true;
                    changed = true;
                    pending.add(raw);
                    continue;
                }
                if (inAdBreak) {
                    pending.add(raw);
                    changed = true;
                    continue;
                }
                if (isStandaloneAdTag(item)) {
                    flush(out, pending);
                    counter.value++;
                    changed = true;
                    continue;
                }
                if (isSegmentTag(item) || isAdSignalTag(item)) {
                    pending.add(raw);
                } else {
                    flush(out, pending);
                    out.add(raw);
                }
                continue;
            }

            if (inAdBreak || hasAdSignal(pending) || isAdSegmentUri(item) || hasAdDomain(item)) {
                pending.clear();
                counter.value++;
                changed = true;
                continue;
            }
            flush(out, pending);
            out.add(raw);
        }

        if (!inAdBreak) {
            flush(out, pending);
        }

        return changed ? String.join("\n", out) : content;
    }

    private static void flush(List<String> out, List<String> pending) {
        out.addAll(pending);
        pending.clear();
    }

    private static boolean hasAdSignal(List<String> pending) {
        for (String line : pending) {
            String t = line.trim();
            if (t.startsWith(TAG_CUE_OUT) || isAdSignalTag(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAdSignalTag(String line) {
        if (line.startsWith("#EXT-OATCLS-SCTE35")) return true;
        if (line.startsWith("#EXT-X-SCTE35")) return true;
        if (line.startsWith("#EXT-X-SPLICEPOINT-SCTE35")) return true;
        if (line.startsWith("#EXT-X-CUE")) return true;
        if (line.startsWith("#EXT-X-ASSET")) return true;
        if (line.startsWith("#EXT-X-VMAP-AD-BREAK")) return true;
        return line.startsWith("#EXT-X-AD");
    }

    private static boolean isSegmentTag(String line) {
        if (line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) return false;
        return line.startsWith(TAG_INF) || line.startsWith("#EXT-X-BYTERANGE")
                || line.startsWith("#EXT-X-PROGRAM-DATE-TIME") || line.startsWith(TAG_DISCONTINUITY)
                || line.startsWith("#EXT-X-PART") || line.startsWith("#EXT-X-PRELOAD-HINT");
    }

    private static boolean isStandaloneAdTag(String line) {
        if (!line.startsWith(TAG_DATERANGE)) return false;
        String lower = line.toLowerCase(Locale.ROOT);
        return isAdLikeText(lower) || lower.contains("x-asset-uri") || lower.contains("x-asset-list");
    }

    private static boolean isAdLikeText(String lower) {
        return lower.contains("scte") || lower.contains("cue") || lower.contains("interstitial")
                || lower.contains("vmap") || lower.contains("vast") || lower.contains("advert")
                || lower.contains("commercial") || lower.contains("ad-") || lower.contains("ad_")
                || lower.contains("ad.") || lower.contains("preroll") || lower.contains("midroll")
                || lower.contains("postroll") || lower.contains("bumper");
    }

    private static boolean isAdSegmentUri(String line) {
        return AD_SEGMENT.matcher(line).find();
    }

    private static boolean hasAdDomain(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (String keyword : AD_DOMAINS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 少数派地址过滤：同一列表里大部分分片来自同一目录/域名，
     * 少量来自另一个 CDN 的分片通常是插播广告。
     */
    private static String removeMinorityUrl(String baseUrl, String content, Counter counter) {
        List<String> lines = List.of(content.split("\n", -1));
        int totalSegments = 0;
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) totalSegments++;
        }
        if (totalSegments < 8) {
            return null;
        }

        Map<String, Integer> byDir = new LinkedHashMap<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String abs = resolve(baseUrl, t);
            String dir = dirOf(abs);
            if (dir.isEmpty()) continue;
            byDir.merge(dir, 1, Integer::sum);
        }
        if (byDir.size() <= 1) return null;

        String mainDir = maxKey(byDir);
        int mainCount = byDir.getOrDefault(mainDir, 0);
        if (mainCount < totalSegments * 0.7) {
            // 目录不够集中，退化为按域名统计
            Map<String, Integer> byHost = new LinkedHashMap<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String host = hostOf(resolve(baseUrl, t));
                if (host.isEmpty()) continue;
                byHost.merge(host, 1, Integer::sum);
            }
            if (byHost.size() <= 1) return null;
            String mainHost = maxKey(byHost);
            int hostCount = byHost.getOrDefault(mainHost, 0);
            if (hostCount < totalSegments * 0.8) return null;
            boolean allLarge = byHost.values().stream().allMatch(v -> v > 15);
            if (allLarge) return null;

            List<String> out = new ArrayList<>();
            for (String line : lines) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    out.add(line);
                    continue;
                }
                String host = hostOf(resolve(baseUrl, t));
                int c = byHost.getOrDefault(host, 0);
                if (host.equals(mainHost) || c > 15) {
                    out.add(line);
                } else {
                    counter.value++;
                }
            }
            return counter.value > totalSegments * 0.3 ? null : String.join("\n", out);
        }

        List<String> out = new ArrayList<>();
        int removed = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                out.add(line);
                continue;
            }
            String dir = dirOf(resolve(baseUrl, t));
            if (dir.equals(mainDir)) {
                out.add(line);
            } else {
                counter.value++;
                removed++;
            }
        }
        if (removed > totalSegments * 0.3) {
            return null;
        }
        return String.join("\n", out);
    }

    /** 按 #EXT-X-DISCONTINUITY 分组，删掉短小的广告组 */
    private static String cleanDiscontinuityGroups(String content, Counter counter) {
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String raw : content.split("\n", -1)) {
            if (raw.trim().startsWith(TAG_DISCONTINUITY) && hasMedia(current)) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(raw);
        }
        if (!current.isEmpty()) groups.add(current);
        if (groups.size() < 3) return content;

        int mainIndex = -1;
        double mainScore = -1;
        for (int i = 0; i < groups.size(); i++) {
            double score = groupScore(groups.get(i));
            if (score > mainScore) {
                mainScore = score;
                mainIndex = i;
            }
        }
        if (mainIndex < 0) return content;

        List<String> main = groups.get(mainIndex);
        int mainSegments = groupSegments(main);
        if (mainSegments < 3) return content;

        String mainHost = groupHost(main);
        String mainDir = groupDir(main);
        double mainDuration = groupDuration(main);

        List<String> out = new ArrayList<>();
        boolean changed = false;
        for (int i = 0; i < groups.size(); i++) {
            List<String> g = groups.get(i);
            if (i == mainIndex || groupSegments(g) == 0) {
                out.addAll(g);
                continue;
            }
            int segs = groupSegments(g);
            double dur = groupDuration(g);
            boolean shortGroup = segs <= 2 || (mainDuration > 0 && dur > 0 && dur < mainDuration * 0.18);
            String host = groupHost(g);
            String dir = groupDir(g);
            boolean differentHost = !mainHost.isEmpty() && !host.isEmpty() && !mainHost.equals(host);
            boolean differentPath = !mainDir.isEmpty() && !dir.isEmpty() && !mainDir.equals(dir);
            boolean adFeature = groupAdLike(g) > 0;
            boolean adLike = adFeature || differentHost || (segs <= 2 && differentPath);

            if (shortGroup && adLike) {
                counter.value += segs;
                changed = true;
            } else {
                out.addAll(g);
            }
        }
        if (counter.value > countSegments(content) * 0.3) {
            return content;
        }
        return changed ? String.join("\n", out) : content;
    }

    private static boolean hasMedia(List<String> group) {
        for (String line : group) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) return true;
        }
        return false;
    }

    private static int groupSegments(List<String> group) {
        int n = 0;
        for (String line : group) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) n++;
        }
        return n;
    }

    private static double groupDuration(List<String> group) {
        double total = 0;
        for (String line : group) {
            Matcher m = EXTINF_VALUE.matcher(line);
            if (m.find()) {
                try {
                    total += Double.parseDouble(m.group(1));
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
        }
        return total;
    }

    private static double groupScore(List<String> group) {
        double duration = groupDuration(group);
        return duration > 0 ? duration : groupSegments(group);
    }

    private static String groupHost(List<String> group) {
        for (String line : group) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                String host = hostOf(t);
                if (!host.isEmpty()) return host;
            }
        }
        return "";
    }

    private static String groupDir(List<String> group) {
        for (String line : group) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("#")) {
                String dir = dirOf(t);
                if (!dir.isEmpty()) return dir;
            }
        }
        return "";
    }

    private static int groupAdLike(List<String> group) {
        int n = 0;
        for (String line : group) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#")) {
                if (isAdSignalTag(t) || isStandaloneAdTag(t)) n++;
            } else if (isAdSegmentUri(t) || hasAdDomain(t)) {
                n++;
            }
        }
        return n;
    }

    /** 把所有分片和 KEY/MAP 地址改写为绝对地址 */
    private static String resolveAll(String baseUrl, String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            String t = line.trim();
            if (t.isEmpty()) {
                sb.append(line).append('\n');
                continue;
            }
            if (t.startsWith("#")) {
                if (t.startsWith(TAG_KEY) || t.startsWith(TAG_MAP)) {
                    sb.append(resolveUriAttribute(baseUrl, line));
                } else {
                    sb.append(line);
                }
                sb.append('\n');
                continue;
            }
            sb.append(resolve(baseUrl, t)).append('\n');
        }
        return sb.toString();
    }

    private static String resolveUriAttribute(String baseUrl, String line) {
        Matcher m = URI_ATTR.matcher(line);
        if (!m.find()) return line;
        String value = m.group(1);
        if (value.startsWith("data:") || value.startsWith("http://") || value.startsWith("https://")) {
            return line;
        }
        return line.replace(value, resolve(baseUrl, value));
    }

    private static String resolve(String baseUrl, String ref) {
        String value = ref.trim();
        if (value.isEmpty()) return value;
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("data:")) {
            return value;
        }
        try {
            return URI.create(baseUrl).resolve(value).toString();
        } catch (Exception e) {
            try {
                return new URI(baseUrl).resolve(value).toString();
            } catch (Exception ignored) {
                return value;
            }
        }
    }

    private static String hostOf(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "";
        int start = url.indexOf("://") + 3;
        int end = url.indexOf('/', start);
        return end > start ? url.substring(start, end) : url.substring(start);
    }

    private static String dirOf(String url) {
        String clean = url;
        int q = clean.indexOf('?');
        if (q >= 0) clean = clean.substring(0, q);
        int slash = clean.lastIndexOf('/');
        return slash > 0 ? clean.substring(0, slash + 1) : "";
    }

    private static String maxKey(Map<String, Integer> map) {
        String key = "";
        int max = -1;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                key = e.getKey();
            }
        }
        return key;
    }

    private static String keepEndList(String original, String result) {
        if (!original.contains(TAG_ENDLIST) || result.contains(TAG_ENDLIST)) {
            return result;
        }
        return result + (result.endsWith("\n") ? "" : "\n") + TAG_ENDLIST + "\n";
    }

    private static boolean isPlayable(String content) {
        boolean pendingInf = false;
        int media = 0;
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith(TAG_INF)) {
                if (pendingInf) return false;
                pendingInf = true;
            } else if (!t.startsWith("#")) {
                media++;
                pendingInf = false;
            } else if (t.startsWith(TAG_ENDLIST) && pendingInf) {
                return false;
            }
        }
        return media > 0 && !pendingInf;
    }

    private static String normalize(String content) {
        StringBuilder sb = new StringBuilder();
        boolean prevBlank = false;
        for (String line : content.replace("\r\n", "\n").split("\n", -1)) {
            boolean blank = line.trim().isEmpty();
            if (blank && prevBlank) continue;
            sb.append(line).append('\n');
            prevBlank = blank;
        }
        return sb.toString();
    }

    /** 供测试/调试：判断是否为 master playlist */
    public static boolean isMaster(String content) {
        return content != null && (content.contains("#EXT-X-STREAM-INF") || content.contains("#EXT-X-MEDIA:"));
    }

    static BigDecimal decimal(String s) {
        try {
            return new BigDecimal(s);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }
}
