package tech.musxi.neteaseloginpoc;

import java.io.IOException;

/**
 * Gate for the verification provider required by playlist subscription writes.
 *
 * <p>MIT NeteaseCloudMusicApiEnhanced/api-enhanced
 * 2aab9957dfd5231e5b192aecdb177f93ace4c92e module/playlist_subscribe.js explicitly selects
 * register_checktoken_v2.js, whose Watchman SDK calls getToken(businessId). An anonymous v3/b
 * response is a different protocol and the previous substitution was rejected by the platform.
 * Until a compatible provider exists, fail before any token acquisition or playlist write.
 * No remote script, browser fingerprint emulation, credential or token is stored here.
 */
final class NeteaseCheckToken {
    String freshV2() throws IOException {
        throw new NeteaseApi.ApiException("verification_required", "网易云歌单收藏需要兼容的平台验证，当前暂不可用");
    }
}
