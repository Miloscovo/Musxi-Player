package tech.musxi.qqloginpoc;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class QqWebAuthorizationTest {
    private String callback(QqWebLoginApi.WebAttempt attempt, String state) throws Exception {
        return "https://y.qq.com/portal/wx_redirect.html?code=fixture-code&state=" + state;
    }
    private String state(QqWebLoginApi.WebAttempt attempt) throws Exception {
        return QqMusicApi.parseQuery(new java.net.URI(attempt.authorizationUrl()).getRawQuery()).get("state");
    }
    @Test public void browserStateIsUniqueAndAuthorizationIsConsumedExactlyOnce() throws Exception {
        QqWebLoginApi.WebAttempt first = QqWebLoginApi.createWebLogin(), second = QqWebLoginApi.createWebLogin();
        assertNotEquals(state(first), state(second));
        QqWebLoginApi.WebAuthorization authorization = QqWebLoginApi.captureWebAuthorization(first, callback(first, state(first)));
        assertFalse(authorization.toString().contains("fixture-code"));
        assertFalse(first.toString().contains(state(first)));
        List<QqMusicApi.Request> requests = new ArrayList<>();
        QqMusicApi api = new QqMusicApi(request -> {
            requests.add(request);
            return new QqMusicApi.Response(200,
                "{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"musicid\":12345,\"musickey\":\"fixture-music-key\",\"encryptUin\":\"fixture-euin\"}}}"
                .getBytes(StandardCharsets.UTF_8), Collections.emptyMap());
        });
        QqWebLoginApi web = new QqWebLoginApi(api);
        assertEquals("12345", web.exchangeWebAuthorization(authorization).musicId);
        assertEquals(1, requests.size());
        assertFalse(requests.get(0).headers.containsKey("Cookie"));
        assertTrue(requests.get(0).body.contains("QQConnectLogin.LoginServer"));
        fails(() -> web.exchangeWebAuthorization(authorization));
        fails(() -> QqWebLoginApi.captureWebAuthorization(first, callback(first, state(first))));
        assertEquals(1, requests.size());
    }
    @Test public void rejectedStateDoesNotConsumeAttemptOrMakeNetworkRequest() throws Exception {
        QqWebLoginApi.WebAttempt attempt = QqWebLoginApi.createWebLogin();
        fails(() -> QqWebLoginApi.captureWebAuthorization(attempt, callback(attempt, "00000000000000000000000000000000")));
        assertNotNull(QqWebLoginApi.captureWebAuthorization(attempt, callback(attempt, state(attempt))));
    }
    @Test public void failedExchangeCannotReplayCode() throws Exception {
        QqWebLoginApi.WebAttempt attempt = QqWebLoginApi.createWebLogin();
        QqWebLoginApi.WebAuthorization authorization = QqWebLoginApi.captureWebAuthorization(attempt, callback(attempt, state(attempt)));
        int[] requests = {0};
        QqMusicApi api = new QqMusicApi(request -> { requests[0]++; throw new IOException("fixture transport"); });
        QqWebLoginApi web = new QqWebLoginApi(api);
        fails(() -> web.exchangeWebAuthorization(authorization));
        fails(() -> web.exchangeWebAuthorization(authorization));
        assertEquals(1, requests[0]);
    }
    private interface Action { void run() throws Exception; }
    private void fails(Action action) throws Exception {
        try { action.run(); fail("must reject"); } catch (IOException expected) {
            assertFalse(expected.getMessage().contains("fixture-code"));
        }
    }
}
