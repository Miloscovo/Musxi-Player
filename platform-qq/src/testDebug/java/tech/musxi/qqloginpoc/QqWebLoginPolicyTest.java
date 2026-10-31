package tech.musxi.qqloginpoc;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QqWebLoginPolicyTest {
    private static final String STATE = "0123456789abcdef0123456789abcdef";

    @Test
    public void authorizationUrlUsesWebCodeFlowAndEncodesRedirect() throws Exception {
        String url = QqWebLoginPolicy.authorizationUrl(STATE);
        URI uri = URI.create(url);

        assertEquals("https", uri.getScheme());
        assertEquals("graph.qq.com", uri.getHost());
        assertEquals("/oauth2.0/authorize", uri.getPath());
        assertTrue(url.contains(
            "redirect_uri=https%3A%2F%2Fy.qq.com%2Fportal%2Fwx_redirect.html"
                + "%3Flogin_type%3D1%26surl%3Dhttps%3A%2F%2Fy.qq.com%2F"));

        Map<String, String> query = QqMusicApi.parseQuery(uri.getRawQuery());
        assertEquals("100497308", query.get("client_id"));
        assertEquals("https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
            query.get("redirect_uri"));
        assertEquals("code", query.get("response_type"));
        assertEquals("get_user_info,get_app_friends", query.get("scope"));
        assertEquals("pc", query.get("display"));
        assertEquals(STATE, query.get("state"));
    }

    @Test
    public void authorizationUrlRejectsInvalidStates() {
        for (String state : new String[] {
            null, "", "0123456789abcdef0123456789abcde", "0123456789abcdef0123456789abcdef0",
            "0123456789ABCDEF0123456789abcdef", "0123456789abcdef0123456789abcdeg",
            "0123456789abcdef0123456789abcde\n"
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> QqWebLoginPolicy.authorizationUrl(state));
        }
    }

    @Test
    public void explicitPasswordPageReturnsToSameBoundOAuthAttempt() throws Exception {
        URI uri = URI.create(QqWebLoginPolicy.passwordLoginUrl(STATE));
        assertEquals("xui.ptlogin2.qq.com", uri.getHost());
        assertEquals("/cgi-bin/xlogin", uri.getPath());
        Map<String, String> query = QqMusicApi.parseQuery(uri.getRawQuery());
        assertEquals("40", query.get("style"));
        assertEquals("716027609", query.get("appid"));
        assertEquals("100497308", query.get("pt_3rd_aid"));
        assertEquals(QqWebLoginPolicy.authorizationUrl(STATE), query.get("s_url"));
        assertTrue(QqWebLoginPolicy.allowsNavigation(uri.toString()));
        assertThrows(IllegalArgumentException.class, () -> QqWebLoginPolicy.passwordLoginUrl("bad"));
    }

    @Test
    public void navigationAllowsOfficialQqLoginAndCaptchaHosts() {
        for (String url : new String[] {
            "https://graph.qq.com/oauth2.0/authorize?client_id=100497308",
            "https://xui.ptlogin2.qq.com/cgi-bin/xlogin?appid=716027609",
            "https://captcha.qq.com/cap_union_new_show?aid=716027609",
            "https://qq.com/",
            "HTTPS://GRAPH.QQ.COM:443/path?q=x#fragment"
        }) {
            assertTrue(url, QqWebLoginPolicy.allowsNavigation(url));
        }
    }

    @Test
    public void navigationRejectsUnsafeOrNoncanonicalAuthorities() {
        for (String url : new String[] {
            null, "", "http://graph.qq.com/oauth2.0/authorize",
            "javascript://graph.qq.com/%0aalert(1)", "https://evil.example/",
            "https://qq.com.evil.example/", "https://evilqq.com/",
            "https://graph.qq.com.evil.example/", "https://graph.qq.com@evil.example/",
            "https://user@graph.qq.com/", "https://graph.qq.com:444/",
            "https://graph.qq.com:/", "https://graph.qq.com:0443/",
            "https://graph.qq.com./", "https://graph%2Eqq.com/",
            "https://127.0.0.1/", "https://qq_com/", " https://qq.com/",
            "https://qq.com/\n"
        }) {
            assertFalse(String.valueOf(url), QqWebLoginPolicy.allowsNavigation(url));
        }
    }

    @Test
    public void callbackRequiresExactHostPathAndAuthority() {
        assertTrue(QqWebLoginPolicy.isCallback(QqWebLoginPolicy.CALLBACK_URL));
        assertTrue(QqWebLoginPolicy.isCallback(
            QqWebLoginPolicy.CALLBACK_URL + "?code=a&state=" + STATE));
        assertTrue(QqWebLoginPolicy.isCallback(
            "HTTPS://Y.QQ.COM:443/portal/wx_redirect.html?code=a&state=" + STATE));

        for (String url : new String[] {
            null, "http://y.qq.com/portal/wx_redirect.html",
            "https://www.y.qq.com/portal/wx_redirect.html",
            "https://y.qq.com.evil.example/portal/wx_redirect.html",
            "https://y.qq.com/portal/wx_redirect.html/",
            "https://y.qq.com/portal/wx_redirect.html.evil",
            "https://y.qq.com/portal/../portal/wx_redirect.html",
            "https://y.qq.com/portal/%77x_redirect.html",
            "https://user@y.qq.com/portal/wx_redirect.html",
            "https://y.qq.com:444/portal/wx_redirect.html",
            "https://y.qq.com:/portal/wx_redirect.html",
            "https://y.qq.com./portal/wx_redirect.html",
            "https://y.qq.com/portal/wx_redirect.html#fragment"
        }) {
            assertFalse(String.valueOf(url), QqWebLoginPolicy.isCallback(url));
        }
    }

    @Test
    public void authorizationCodeReturnsOnlySafeCodeForMatchingState() throws Exception {
        assertEquals("auth_code-123",
            QqWebLoginPolicy.authorizationCode(
                QqWebLoginPolicy.CALLBACK_URL + "?code=auth_code-123&state=" + STATE, STATE));
        assertEquals("ABC.def~ghi",
            QqWebLoginPolicy.authorizationCode(
                QqWebLoginPolicy.CALLBACK_URL + "?state=" + STATE + "&code=ABC.def%7Eghi", STATE));
    }

    @Test
    public void authorizationCodeRejectsWrongMissingAndMalformedState() {
        for (String url : new String[] {
            QqWebLoginPolicy.CALLBACK_URL + "?code=safe",
            QqWebLoginPolicy.CALLBACK_URL + "?code=safe&state=ffffffffffffffffffffffffffffffff",
            QqWebLoginPolicy.CALLBACK_URL + "?code=safe&state=0123456789ABCDEF0123456789abcdef",
            QqWebLoginPolicy.CALLBACK_URL + "?code=safe&state=" + STATE + "&state=" + STATE
        }) {
            assertThrows(IOException.class,
                () -> QqWebLoginPolicy.authorizationCode(url, STATE));
        }
        assertThrows(IOException.class,
            () -> QqWebLoginPolicy.authorizationCode(
                QqWebLoginPolicy.CALLBACK_URL + "?code=safe&state=" + STATE, null));
    }

    @Test
    public void authorizationCodeRejectsCallbackShapeAndAmbiguousQueries() {
        for (String url : new String[] {
            "http://y.qq.com/portal/wx_redirect.html?code=safe&state=" + STATE,
            "https://y.qq.com/portal/wx_redirect.html.evil?code=safe&state=" + STATE,
            "https://evil.example/portal/wx_redirect.html?code=safe&state=" + STATE,
            "https://user@y.qq.com/portal/wx_redirect.html?code=safe&state=" + STATE,
            "https://y.qq.com:444/portal/wx_redirect.html?code=safe&state=" + STATE,
            "https://y.qq.com:/portal/wx_redirect.html?code=safe&state=" + STATE,
            "https://y.qq.com/portal/wx_redirect.html?code=safe&state=" + STATE + "#fragment",
            QqWebLoginPolicy.CALLBACK_URL + "?code=a&code=b&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=a&%63ode=b&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=a&&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=a&state=" + STATE + "&",
            QqWebLoginPolicy.CALLBACK_URL + "?code=a=b&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?=x&code=a&state=" + STATE
        }) {
            assertThrows(IOException.class,
                () -> QqWebLoginPolicy.authorizationCode(url, STATE));
        }
    }

    @Test
    public void authorizationCodeRejectsErrorEmptyUnsafeAndOversizeCodes() throws Exception {
        for (String url : new String[] {
            QqWebLoginPolicy.CALLBACK_URL + "?error=access_denied&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?error=access_denied&code=safe&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=bad%20code&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=bad%0Acode&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=bad%00code&state=" + STATE,
            QqWebLoginPolicy.CALLBACK_URL + "?code=" + repeat('a', 4097) + "&state=" + STATE
        }) {
            assertThrows(IOException.class,
                () -> QqWebLoginPolicy.authorizationCode(url, STATE));
        }

        String maximum = repeat('a', 4096);
        assertEquals(maximum, QqWebLoginPolicy.authorizationCode(
            QqWebLoginPolicy.CALLBACK_URL + "?code=" + maximum + "&state=" + STATE, STATE));
    }

    @Test
    public void callbackErrorsNeverContainCodeOrUrl() {
        String secret = "do-not-leak-code";
        String url = QqWebLoginPolicy.CALLBACK_URL
            + "?code=" + secret + "&code=second-secret&state=" + STATE;
        try {
            QqWebLoginPolicy.authorizationCode(url, STATE);
            fail("expected rejection");
        } catch (IOException error) {
            assertFalse(error.getMessage().contains(secret));
            assertFalse(error.getMessage().contains("second-secret"));
            assertFalse(error.getMessage().contains("/portal/wx_redirect.html"));
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
