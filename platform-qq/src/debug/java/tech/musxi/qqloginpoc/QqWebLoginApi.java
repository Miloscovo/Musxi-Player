package tech.musxi.qqloginpoc;

import java.io.IOException;
import java.util.UUID;

/** Debug-only experiment. Does not replace the production official-QQ-app login. */
public final class QqWebLoginApi {
    private final QqMusicApi api;
    public QqWebLoginApi(QqMusicApi api) { this.api = java.util.Objects.requireNonNull(api); }

    public static final class WebAttempt {
        private final String state;
        private final long expires = System.nanoTime() + 180_000_000_000L;
        private boolean captured;
        private WebAttempt(String state) { this.state = state; }
        public String authorizationUrl() { return QqWebLoginPolicy.authorizationUrl(state); }
        public String passwordLoginUrl() { return QqWebLoginPolicy.passwordLoginUrl(state); }
        public boolean isCurrent() { return System.nanoTime() < expires; }
        @Override public String toString() { return "WebAttempt(redacted)"; }
    }

    /** One-use authorization code never crosses into UI state, reports or storage. */
    public static final class WebAuthorization {
        private final WebAttempt attempt;
        private String code;
        private WebAuthorization(WebAttempt attempt, String code) { this.attempt = attempt; this.code = code; }
        private synchronized String consume() throws IOException {
            if (code == null || !attempt.isCurrent()) {
                code = null;
                throw invalid();
            }
            String result = code; code = null; return result;
        }
        @Override public String toString() { return "WebAuthorization(redacted)"; }
    }

    public static WebAttempt createWebLogin() {
        return new WebAttempt(UUID.randomUUID().toString().replace("-", ""));
    }

    public static WebAuthorization captureWebAuthorization(WebAttempt attempt, String callback) throws IOException {
        if (attempt == null) throw invalid();
        synchronized (attempt) {
            if (!attempt.isCurrent() || attempt.captured) throw invalid();
            String code = QqWebLoginPolicy.authorizationCode(callback, attempt.state);
            attempt.captured = true;
            return new WebAuthorization(attempt, code);
        }
    }

    public QqMusicApi.Session exchangeWebAuthorization(WebAuthorization authorization) throws IOException {
        if (authorization == null) throw invalid();
        return api.exchangeMusicCode(authorization.consume());
    }

    private static IOException invalid() {
        return new QqMusicApi.ApiException("request", "网页登录请求已失效，请重新打开");
    }
}
