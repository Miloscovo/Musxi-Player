package tech.musxi.qqloginpoc;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Debug-only navigation and callback policy for the unverified QQ Connect web fallback. */
public final class QqWebLoginPolicy {
    public static final String AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize";
    public static final String CALLBACK_URL = "https://y.qq.com/portal/wx_redirect.html";

    private static final String CALLBACK_HOST = "y.qq.com";
    private static final String CALLBACK_PATH = "/portal/wx_redirect.html";
    private static final String REDIRECT_URI =
        "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/";
    private static final int URL_LIMIT = 8192;
    private static final int CODE_LIMIT = 4096;
    private static final Pattern STATE = Pattern.compile("\\A[a-f0-9]{32}\\z");
    private static final Pattern CODE =
        Pattern.compile("\\A[A-Za-z0-9_!~.*'()%+/:=@-]{1," + CODE_LIMIT + "}\\z");
    private static final Pattern DNS_LABEL =
        Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?");

    private QqWebLoginPolicy() {}

    /** Ordinary official web view: the mobile authorization page requires the QQ app on this device. */
    public static String authorizationUrl(String state) {
        if (!validState(state)) {
            throw new IllegalArgumentException("QQ web login state must be 32 lowercase hex characters");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", "100497308");
        params.put("redirect_uri", REDIRECT_URI);
        params.put("response_type", "code");
        params.put("scope", "get_user_info,get_app_friends");
        params.put("display", "pc");
        params.put("state", state);
        return AUTHORIZE_URL + "?" + QqMusicApi.query(params);
    }

    /** Explicit official password-capable page; mobile authorize otherwise redirects to app-only UI. */
    public static String passwordLoginUrl(String state) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("appid", "716027609");
        params.put("pt_3rd_aid", "100497308");
        params.put("daid", "383");
        params.put("style", "40");
        params.put("target", "self");
        params.put("s_url", authorizationUrl(state));
        return "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?" + QqMusicApi.query(params);
    }

    /** Allows only unambiguous HTTPS DNS authorities on qq.com or one of its subdomains. */
    public static boolean allowsNavigation(String url) {
        URI uri = parse(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null || !validPort(uri)) return false;
        String host = canonicalHost(uri);
        if (host == null) return false;
        return host.equals("qq.com") || host.endsWith(".qq.com");
    }

    /** Identifies the QQ redirect by exact HTTPS host and path; the callback query is optional. */
    public static boolean isCallback(String url) {
        return callbackUri(url) != null;
    }

    /**
     * Extracts a validated authorization code from an exact QQ callback.
     * Error messages deliberately contain neither the callback URL nor the code.
     */
    public static String authorizationCode(String url, String expectedState) throws IOException {
        if (!validState(expectedState)) throw invalidCallback();
        URI uri = callbackUri(url);
        if (uri == null) throw invalidCallback();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isEmpty() || !unambiguousQuery(rawQuery)) {
            throw invalidCallback();
        }

        Map<String, String> query;
        try {
            query = QqMusicApi.parseQuery(rawQuery);
        } catch (IOException invalid) {
            throw invalidCallback();
        }
        if (hasError(query) || !expectedState.equals(query.get("state"))) {
            throw invalidCallback();
        }
        String code = query.get("code");
        if (code == null || !CODE.matcher(code).matches()) throw invalidCallback();
        return code;
    }

    private static URI callbackUri(String url) {
        URI uri = parse(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !validPort(uri) || !CALLBACK_PATH.equals(uri.getRawPath())) return null;
        return CALLBACK_HOST.equalsIgnoreCase(canonicalHost(uri)) ? uri : null;
    }

    private static URI parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > URL_LIMIT) return null;
        try {
            URI uri = new URI(raw);
            return uri.isAbsolute() && !uri.isOpaque() ? uri : null;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private static boolean validPort(URI uri) {
        return uri.getPort() == -1 || uri.getPort() == 443;
    }

    /**
     * Returns a normalized host only when the raw authority is exactly the DNS host with either
     * no port or :443. This rejects userinfo, empty/noncanonical ports, IP literals, IDNs,
     * trailing dots, and encoded authority syntax.
     */
    private static String canonicalHost(URI uri) {
        String host = uri.getHost();
        String authority = uri.getRawAuthority();
        if (host == null || authority == null || host.length() > 253) return null;
        String expectedAuthority = host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!expectedAuthority.equalsIgnoreCase(authority)) return null;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63 || !DNS_LABEL.matcher(label).matches()) {
                return null;
            }
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static boolean unambiguousQuery(String rawQuery) {
        for (String pair : rawQuery.split("&", -1)) {
            if (pair.isEmpty()) return false;
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals != pair.lastIndexOf('=')) return false;
        }
        return true;
    }

    private static boolean hasError(Map<String, String> query) {
        for (String name : query.keySet()) {
            if ("error".equalsIgnoreCase(name) || "error_description".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean validState(String state) {
        return state != null && STATE.matcher(state).matches();
    }

    private static IOException invalidCallback() {
        return new IOException("QQ web login callback is invalid");
    }
}
