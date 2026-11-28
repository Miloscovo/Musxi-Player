package tech.musxi.neteaseloginpoc;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Main-thread ordering for this app's dedicated login jar. No cookie values leave this class. */
public final class WebLoginSession {
    public interface Completion { void complete(boolean success); }
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Runnable> WINDOWS = new HashSet<>();
    private static final ArrayList<Completion> WAITERS = new ArrayList<>();
    private static boolean clearing;
    private static final String PREFS = "netease-browser-lifecycle-v1";
    private WebLoginSession() {}

    /** Durable intent must precede deletion of the native credentials, even if the process dies. */
    public static void markForClear(Context context) {
        synchronized (WebLoginSession.class) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean("pending", true).putString("revision", UUID.randomUUID().toString()).commit())
                throw new IllegalStateException("Browser cleanup could not be recorded");
        }
    }

    public static void prepare(Context context, Completion completion) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (clearing || Build.VERSION.SDK_INT < 28 ||
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("pending", true)) {
                clearOnMain(app, completion);
            } else completion.complete(true);
        });
    }

    /** Explicit logout and legacy close use the same serialized purge; opens wait for completion. */
    public static void clear(Context context, Completion completion) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> clearOnMain(app, completion));
    }

    private static void clearOnMain(Context app, Completion completion) {
        final boolean[] delivered = { false };
        Completion once = success -> {
            if (!delivered[0]) { delivered[0] = true; completion.complete(success); }
        };
        WAITERS.add(once);
        MAIN.postDelayed(() -> {
            if (WAITERS.remove(once)) once.complete(false);
        }, 15_000L);
        if (clearing) return;
        clearing = true;
        try {
            markForClear(app);
            final String revision = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("revision", "");
            for (Runnable close : new ArrayList<>(WINDOWS)) close.run();
            WebStorage.getInstance().deleteAllData();
            CookieManager.getInstance().removeAllCookies(removed -> {
                boolean success = false;
                try {
                    CookieManager.getInstance().flush();
                    // false means no cookies were removed, including an already empty jar.
                    synchronized (WebLoginSession.class) {
                        if (!CookieManager.getInstance().hasCookies() && revision.equals(
                                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("revision", ""))) {
                            success = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                                    .putBoolean("pending", false).commit();
                        }
                    }
                } catch (RuntimeException ignored) { /* durable pending flag stays set */ }
                finish(success);
            });
        } catch (RuntimeException unavailable) { finish(false); }
    }

    static void opened(Runnable close) { WINDOWS.add(close); }
    static void closed(Context context, Runnable close) {
        WINDOWS.remove(close);
        if (Build.VERSION.SDK_INT < 28 && !clearing) clear(context, ignored -> {});
        else CookieManager.getInstance().flush();
    }
    private static void finish(boolean success) {
        clearing = false;
        ArrayList<Completion> callbacks = new ArrayList<>(WAITERS);
        WAITERS.clear();
        for (Completion completion : callbacks) completion.complete(success);
    }
}
