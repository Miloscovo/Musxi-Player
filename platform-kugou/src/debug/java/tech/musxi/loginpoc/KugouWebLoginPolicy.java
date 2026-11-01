package tech.musxi.loginpoc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Debug-only navigation and cookie policy for the official Kugou web login. */
public final class KugouWebLoginPolicy {
    public static final String LOGIN_URL =
        "https://login-user.kugou.com/login/?appid=1014"
            + "&ref=https://www.kugou.com/reg/web/"
            + "&redirect_uri=https://staticssl.kugou.com/common/html/login/regok.html"
            + "&callback=UsLoginCallback";

    private static final String COOKIE_NAME = "KuGoo";
    private static final String COOKIE_DOMAIN = "kugou.com";
    private static final String NICKNAME = "\u9177\u72D7\u7F51\u9875\u7528\u6237";
    private static final int URL_LIMIT = 8192;
    private static final int COOKIE_LIMIT = 8192;
    private static final int FIELD_VALUE_LIMIT = 4096;
    private static final Pattern DNS_LABEL =
        Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?");

    private KugouWebLoginPolicy() {}

    /**
     * Allows only canonical HTTPS authorities on kugou.com or its subdomains.
     * Userinfo, non-443 ports, IP literals, encoded authorities and malformed URLs fail closed.
     */
    public static boolean allowsNavigation(String url) {
        URI uri = parse(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null || !validPort(uri)) return false;
        String host = canonicalHost(uri);
        if (host == null) return false;
        return host.equals(COOKIE_DOMAIN) || host.endsWith("." + COOKIE_DOMAIN);
    }

    /**
     * Parses exactly one non-duplicated KuGoo cookie carrying KugooID and t.
     * Cookie values use the browser percent encoding and the official script's
     * encodeURIComponent/decodeURIComponent convention. Error messages contain no input.
     */
    public static KugouApi.Session parseCookies(String cookieHeader) throws IOException {
        ParsedCookie cookie = findCookie(cookieHeader);
        String rawValue = cookie.value;
        String fieldsText = rawValue.contains("=") ? rawValue
            : strictDecode(rawValue, true, COOKIE_LIMIT);
        Map<String, String> fields = new LinkedHashMap<>();
        String[] pairs = fieldsText.split("&", -1);
        if (pairs.length > 32) throw invalidCookie();
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals <= 0) throw invalidCookie();
            String name = strictDecode(pair.substring(0, equals), false, FIELD_VALUE_LIMIT);
            if (!name.equals("KugooID") && !name.equals("t")) continue;
            String value = strictDecode(
                pair.substring(equals + 1), true, FIELD_VALUE_LIMIT);
            if (fields.containsKey(name) || value.isEmpty()) throw invalidCookie();
            fields.put(name, value);
        }
        String userId = fields.get("KugooID");
        String token = fields.get("t");
        if (userId == null || token == null) throw invalidCookie();
        try {
            return new KugouApi.Session(token, userId, NICKNAME);
        } catch (IllegalArgumentException invalidCredentials) {
            throw invalidCookie();
        }
    }

    private static ParsedCookie findCookie(String header) throws IOException {
        if (header == null || header.isEmpty() || header.length() > COOKIE_LIMIT
                || hasControl(header)) throw invalidCookie();
        ParsedCookie found = null;
        for (String part : header.split(";", -1)) {
            String item = trimOws(part);
            if (item.isEmpty()) continue;
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String name = item.substring(0, equals);
            if (!COOKIE_NAME.equals(name)) continue;
            String value = item.substring(equals + 1);
            if (found != null || value.length() > COOKIE_LIMIT) throw invalidCookie();
            found = new ParsedCookie(value);
        }
        if (found == null) throw invalidCookie();
        return found;
    }

    private static String strictDecode(
            String text, boolean fieldValue, int limit) throws IOException {
        if (text.length() > limit) throw invalidCookie();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%') {
                if (i + 2 >= text.length()) throw invalidCookie();
                int high = hex(text.charAt(i + 1));
                int low = hex(text.charAt(i + 2));
                if (high < 0 || low < 0) throw invalidCookie();
                bytes.write((high << 4) | low);
                i += 2;
            } else {
                if (c == '+' && !fieldValue) throw invalidCookie();
                if (c > 0x7e) throw invalidCookie();
                bytes.write(c);
            }
        }
        String decoded;
        try {
            decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
        } catch (CharacterCodingException invalid) {
            throw invalidCookie();
        }
        if (decoded.isEmpty() || hasControl(decoded)) throw invalidCookie();
        return decoded;
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private static String trimOws(String value) {
        int start = 0, end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || Character.isISOControl(c)) return true;
        }
        return false;
    }

    private static URI parse(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > URL_LIMIT || hasControl(raw)) {
            return null;
        }
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

    private static String canonicalHost(URI uri) {
        String host = uri.getHost();
        String authority = uri.getRawAuthority();
        if (host == null || authority == null || host.length() > 253) return null;
        String expected = host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        if (!expected.equalsIgnoreCase(authority)) return null;
        for (String label : host.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || !DNS_LABEL.matcher(label).matches()) return null;
        }
        return host.toLowerCase(Locale.ROOT);
    }

    private static IOException invalidCookie() {
        return new IOException("Kugou web login cookie is invalid");
    }

    private record ParsedCookie(String value) {}
}
