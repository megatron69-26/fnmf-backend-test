package com.llmgateway.service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tiện ích chuẩn hóa URL tin tức để phục vụ chống trùng lặp dữ liệu (Deduplication).
 * Loại bỏ các query tracking (utm_*, ref, fbclid...), xóa trailing slash và fragment,
 * chuyển scheme và host về chữ thường.
 */
public final class NewsUrlNormalizer {

    private static final Set<String> TRACKING_PARAMS;

    static {
        Set<String> set = new HashSet<>();
        set.add("utm_source");
        set.add("utm_medium");
        set.add("utm_campaign");
        set.add("utm_term");
        set.add("utm_content");
        set.add("utm_id");
        set.add("ref");
        set.add("reference");
        set.add("fbclid");
        set.add("gclid");
        set.add("_ga");
        set.add("mc_cid");
        set.add("mc_eid");
        TRACKING_PARAMS = Collections.unmodifiableSet(set);
    }

    private NewsUrlNormalizer() {
    }

    public static String normalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        String input = rawUrl.trim();
        // Loại bỏ fragment nếu có
        int fragmentIndex = input.indexOf('#');
        if (fragmentIndex >= 0) {
            input = input.substring(0, fragmentIndex);
        }

        try {
            URI uri = URI.create(input);
            String scheme = uri.getScheme();
            if (scheme == null) {
                return input;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);

            String host = uri.getHost();
            if (host == null) {
                return input;
            }
            host = host.toLowerCase(Locale.ROOT);

            int port = uri.getPort();
            String authority;
            if (port == -1 || ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
                authority = host;
            } else {
                authority = host + ":" + port;
            }

            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                path = "";
            } else {
                // Xóa trailing slash nếu đường dẫn dài hơn 1 ký tự
                while (path.length() > 1 && path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
            }

            String query = uri.getRawQuery();
            String cleanQuery = cleanQueryParams(query);

            StringBuilder sb = new StringBuilder();
            sb.append(scheme).append("://").append(authority).append(path);
            if (cleanQuery != null && !cleanQuery.isEmpty()) {
                sb.append("?").append(cleanQuery);
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback nếu URL có ký tự đặc biệt không parse được bằng URI tiêu chuẩn
            return fallbackNormalize(input);
        }
    }

    private static String cleanQueryParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }

        String[] pairs = rawQuery.split("&");
        List<String> kept = new ArrayList<>();
        for (String pair : pairs) {
            if (pair.isBlank()) continue;
            int eqIdx = pair.indexOf('=');
            String key = (eqIdx >= 0 ? pair.substring(0, eqIdx) : pair).trim();
            String lowerKey = key.toLowerCase(Locale.ROOT);
            if (!TRACKING_PARAMS.contains(lowerKey)) {
                kept.add(pair);
            }
        }
        if (kept.isEmpty()) {
            return null;
        }
        return String.join("&", kept);
    }

    private static String fallbackNormalize(String input) {
        String res = input.trim();
        int qIdx = res.indexOf('?');
        if (qIdx >= 0) {
            String base = res.substring(0, qIdx);
            String query = res.substring(qIdx + 1);
            String cleanedQuery = cleanQueryParams(query);
            while (base.length() > 8 && base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return cleanedQuery != null ? base + "?" + cleanedQuery : base;
        } else {
            while (res.length() > 8 && res.endsWith("/")) {
                res = res.substring(0, res.length() - 1);
            }
            return res;
        }
    }
}
