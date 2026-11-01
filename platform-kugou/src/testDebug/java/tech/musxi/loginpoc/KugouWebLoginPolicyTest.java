package tech.musxi.loginpoc;

import java.io.IOException;
import java.net.URI;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KugouWebLoginPolicyTest {
    @Test
    public void loginUrlUsesOfficialPopupShape() throws Exception {
        URI uri = URI.create(KugouWebLoginPolicy.LOGIN_URL);
        assertEquals("https", uri.getScheme());
        assertEquals("login-user.kugou.com", uri.getHost());
        assertEquals("/login/", uri.getPath());
        assertEquals("appid=1014&ref=https://www.kugou.com/reg/web/"
            + "&redirect_uri=https://staticssl.kugou.com/common/html/login/regok.html"
            + "&callback=UsLoginCallback",
            uri.getRawQuery());
        assertTrue(KugouWebLoginPolicy.allowsNavigation(KugouWebLoginPolicy.LOGIN_URL));
    }

    @Test
    public void navigationAllowsOnlyCanonicalHttpsKugouHosts() {
        for (String url : new String[] {
            "HTTPS://KUGOU.COM/",
            "HTTPS://LOGIN-USER.KUGOU.COM:443/",
            "https://login-user.kugou.com/login/",
            "https://staticssl.kugou.com/common/js/min/login/base.min.js",
            "https://h5.kugou.com/apps/loginQRCode/html/index.html"
        }) {
            assertTrue(url, KugouWebLoginPolicy.allowsNavigation(url));
        }
    }

    @Test
    public void navigationRejectsMaliciousAuthorities() {
        for (String url : new String[] {
            null, "", "http://www.kugou.com/",
            "javascript://www.kugou.com/%0aalert(1)",
            "https://evil.example/", "https://kugou.com.evil.example/",
            "https://evil-kugou.com/", "https://kugou.com@evil.example/",
            "https://user@login-user.kugou.com/",
            "https://login-user.kugou.com:444/",
            "https://login-user.kugou.com:/",
            "https://login-user.kugou.com:0443/",
            "https://login-user.kugou.com./",
            "https://login-user.kugou%2Ecom/",
            "https://127.0.0.1/", "https://kugou_com/",
            " https://www.kugou.com/", "https://www.kugou.com/\n",
            "https://www.kugou.com/#\u0000"
        }) {
            assertFalse(String.valueOf(url), KugouWebLoginPolicy.allowsNavigation(url));
        }
    }

    @Test
    public void parsesOneCookieAndDecodesFields() throws Exception {
        String header = "kg_mid=device; KuGoo=KugooID=1234567890&t=token%2Bvalue"
            + "&NickName=%E6%B5%8B%E8%AF%95; a_id=1014";
        KugouApi.Session session = KugouWebLoginPolicy.parseCookies(header);
        assertEquals("1234567890", session.userId);
        assertEquals("token+value", session.token);
    }

    @Test
    public void acceptsBrowserEncodedCookieNameAndCommonCookieSpacing() throws Exception {
        KugouApi.Session session = KugouWebLoginPolicy.parseCookies(
            " KuGoo=KugooID%3D42%26t%3Dabc%2Fdef ; kg_mid=x ");
        assertEquals("42", session.userId);
        assertEquals("abc/def", session.token);
    }

    @Test
    public void ignoresOptionalEmptyAndLegacyEscapedFields() throws Exception {
        KugouApi.Session session = KugouWebLoginPolicy.parseCookies(
            "KuGoo=KugooID=42&t=abc%3D%3D&NickName=%u6D4B&UserName=&Pic=");
        assertEquals("42", session.userId);
        assertEquals("abc==", session.token);
    }

    @Test
    public void outerEncodedCookieAllowsEqualsInToken() throws Exception {
        KugouApi.Session session = KugouWebLoginPolicy.parseCookies(
            "KuGoo=KugooID%3D42%26t%3Da%3Db%3D%3D%26NickName%3D");
        assertEquals("42", session.userId);
        assertEquals("a=b==", session.token);
    }

    @Test
    public void rejectsMissingDuplicateOrAmbiguousRequiredFields() {
        for (String header : new String[] {
            null, "", "kg_mid=x", "KuGoo=KugooID=42",
            "KuGoo=t=abc", "KuGoo=KugooID=42&t=",
            "KuGoo=KugooID=&t=abc",
            "KuGoo=KugooID=42&KugooID=43&t=abc",
            "KuGoo=KugooID=42&t=abc&t=def",
            "KuGoo=KugooID=42&t=abc; KuGoo=KugooID=42&t=def"
        }) {
            assertThrows(IOException.class,
                () -> KugouWebLoginPolicy.parseCookies(header));
        }
    }

    @Test
    public void rejectsBadEncodingControlAndOversizeValues() {
        for (String header : new String[] {
            "KuGoo=KugooID=42&t=abc%",
            "KuGoo=KugooID=42&t=abc%0Adef",
            "KuGoo=KugooID=42&t=abc%C0%AF",
            "KuGoo=KugooID=42&t=abc\u0001def",
            "\tKuGoo=KugooID=42&t=abc",
            "KuGoo=KugooID=42&t=" + repeat('a', 81),
            "KuGoo=KugooID=0&t=abc",
            "KuGoo=KugooID=42&t=abc; KuGooX=" + repeat('x', 9000)
        }) {
            assertThrows(IOException.class,
                () -> KugouWebLoginPolicy.parseCookies(header));
        }
    }

    @Test
    public void cookieErrorsContainNoInputValue() {
        String secret = "do-not-leak-token";
        String header = "KuGoo=KugooID=42&t=" + secret + "&t=" + secret;
        try {
            KugouWebLoginPolicy.parseCookies(header);
            fail("expected rejection");
        } catch (IOException error) {
            assertFalse(error.getMessage().contains(secret));
            assertFalse(error.getMessage().contains("KugooID"));
        }
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
