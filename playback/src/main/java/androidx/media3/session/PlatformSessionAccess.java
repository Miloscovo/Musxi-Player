package androidx.media3.session;

import android.annotation.SuppressLint;
import androidx.media3.common.util.UnstableApi;

/** Version-pinned Media3 1.8.0 access. Reads the existing session; never creates a second one. */
@UnstableApi
@SuppressLint("RestrictedApi")
public final class PlatformSessionAccess {
    private PlatformSessionAccess() {}

    public static android.media.session.MediaSession getPlatformSession(MediaSession session) {
        Object platform = session.getImpl().getMediaSessionLegacyStub().getSessionCompat().getMediaSession();
        if (!(platform instanceof android.media.session.MediaSession)) {
            throw new IllegalStateException("Platform media session unavailable");
        }
        return (android.media.session.MediaSession) platform;
    }
}
