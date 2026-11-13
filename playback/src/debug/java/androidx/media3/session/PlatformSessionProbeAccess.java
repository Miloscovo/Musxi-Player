package androidx.media3.session;

import android.annotation.SuppressLint;
import androidx.media3.common.util.UnstableApi;

/** Debug-only, version-pinned access to Media3 1.8.0's existing platform session. */
@UnstableApi
@SuppressLint("RestrictedApi")
public final class PlatformSessionProbeAccess {
    private PlatformSessionProbeAccess() {}

    public static android.media.session.MediaSession getPlatformSession(MediaSession session) {
        Object platformSession = session.getImpl()
                .getMediaSessionLegacyStub()
                .getSessionCompat()
                .getMediaSession();
        if (!(platformSession instanceof android.media.session.MediaSession)) {
            throw new IllegalStateException("Platform media session unavailable");
        }
        return (android.media.session.MediaSession) platformSession;
    }
}
