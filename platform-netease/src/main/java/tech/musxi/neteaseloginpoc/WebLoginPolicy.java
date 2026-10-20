package tech.musxi.neteaseloginpoc;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Pure Java policy shared by browser navigation and request-Cookie import. */
public final class WebLoginPolicy {
    public static final String LOGIN_URL = "https://y.music.163.com/m/login";
    private static final String[] DOMAINS = {
        "music.163.com", "reg.163.com", "qq.com", "weibo.com", "weibo.cn"
    };

    private WebLoginPolicy() {}

    public static boolean allowsNavigation(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost();
            if (host == null || host.length() > 253) return false;
            // Reject empty ports, encoded authorities, IP literals and non-DNS labels.
            String authority = host + (uri.getPort() == -1 ? "" : ":443");
            if (!authority.equalsIgnoreCase(uri.getRawAuthority())) return false;
            for (String label : host.split("\\.", -1)) {
                if (label.length() > 63 || !label.matches("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
                    return false;
            }
            host = host.toLowerCase(Locale.ROOT);
            for (String domain : DOMAINS) {
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
            return false;
        } catch (URISyntaxException invalid) {
            return false;
        }
    }

    /** Parses CookieManager.getCookie output, never Set-Cookie attributes. */
    public static Map<String,String> parseMusicCookies(String header) throws IOException {
        if (header == null || header.length() > 32768) throw invalidCookies();
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) throw invalidCookies();
        }
        Map<String,String> cookies = new LinkedHashMap<>();
        for (String part : header.split(";", -1)) {
            int start = 0;
            while (start < part.length() && (part.charAt(start) == ' ' || part.charAt(start) == '\t')) start++;
            int equals = part.indexOf('=', start);
            String name = part.substring(start, equals == -1 ? part.length() : equals);
            if (!"MUSIC_U".equals(name) && !"__csrf".equals(name)) continue;
            if (equals == -1 || cookies.containsKey(name)) throw invalidCookies();
            String value = part.substring(equals + 1);
            if (!safeValue(value, "MUSIC_U".equals(name) ? 8192 : 256)
                    || ("MUSIC_U".equals(name) && value.isEmpty())) throw invalidCookies();
            cookies.put(name, value);
        }
        if (!cookies.containsKey("MUSIC_U")) throw invalidCookies();
        return cookies;
    }

    private static boolean safeValue(String value, int limit) {
        if (value.length() > limit) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '!' || c > '~' || c == ';' || c == ',' || c == '"' || c == '\\') return false;
        }
        return true;
    }

    private static IOException invalidCookies() {
        return new IOException("浏览器网易云登录凭证缺失或格式无效");
    }
}
