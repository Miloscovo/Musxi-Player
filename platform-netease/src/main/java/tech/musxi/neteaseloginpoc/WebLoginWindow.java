package tech.musxi.neteaseloginpoc;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.BooleanSupplier;

/**
 * Fallback Activity-owned Dialog wrapper around {@link WebLoginHost} for non-Compose debug
 * harnesses and unit tests. Product UI hosts {@link WebLoginHost} directly inside Musxi Compose.
 */
public final class WebLoginWindow {
    public interface Listener {
        void ready(boolean firstParty, boolean thirdParty);
        void cookies(String header);
        void cancelled();
    }

    private final Dialog dialog;
    private final LinearLayout container;
    private final TextView status;
    private final WebLoginHost host;
    private boolean closing;

    public WebLoginWindow(Activity activity, Listener listener) {
        this(activity, listener, () -> true);
    }

    public WebLoginWindow(Activity activity, Listener listener, BooleanSupplier current) {
        dialog = new Dialog(activity, android.R.style.Theme_Material_Light_NoActionBar);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
        container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.WHITE);
        status = new TextView(activity);
        status.setText("正在准备网页登录…");
        status.setTextColor(Color.BLACK);
        status.setPadding(20, 20, 20, 12);
        container.addView(status);
        Button done = new Button(activity);
        done.setText("我已完成网页登录，验证并返回");
        container.addView(done);
        Button reload = new Button(activity);
        reload.setText("重新加载官方登录页");
        container.addView(reload);
        Button cancel = new Button(activity);
        cancel.setText("取消网页登录");
        container.addView(cancel);

        dialog.setContentView(container);
        dialog.show();
        host = new WebLoginHost(activity, container, new WebLoginHost.Listener() {
            @Override public void stateChanged(WebLoginHost.Stage stage, String domain, int progress,
                    boolean canSubmit, boolean canReload, boolean canGoBack) {
                status.setText(statusText(stage, domain));
            }
            @Override public void ready(boolean firstParty, boolean thirdParty) {
                listener.ready(firstParty, thirdParty);
            }
            @Override public void cookies(String header) {
                listener.cookies(header);
                close();
            }
            @Override public void cancelled() {
                listener.cancelled();
                if (!closing) close();
            }
        }, current);
        done.setOnClickListener(v -> host.submit());
        reload.setOnClickListener(v -> host.reload());
        cancel.setOnClickListener(v -> close());
        dialog.setOnDismissListener(ignored -> {
            closing = true;
            host.close();
        });
    }

    public void close() {
        if (closing) return;
        closing = true;
        host.close();
        if (dialog.isShowing()) dialog.dismiss();
    }

    private static String statusText(WebLoginHost.Stage stage, String domain) {
        switch (stage) {
            case PREPARING: return "正在准备网页登录…";
            case LOADING: return "正在加载官方登录页…";
            case READY: return domain == null || domain.isEmpty()
                    ? "官方网页登录已就绪，完成登录后点击上方验证按钮。"
                    : String.format("官方网页登录 · %s\n完成登录后点击上方验证按钮。", domain);
            case COOKIE_UNAVAILABLE: return "网页登录 Cookie 读写检查未通过，可重新打开或改用相册扫码。";
            case CLEANUP_FAILED: return "网页登录会话清理未完成，请关闭后重试。";
            case NAVIGATION_BLOCKED: return "当前跳转未在此登录窗口支持，请在官方网页使用手机号等方式，或改用相册扫码。";
            case SSL_ERROR: return "网页安全连接未通过，请检查网络和系统时间后重新加载。";
            case LOAD_ERROR: return "官方网页加载未完成，请检查网络后重新加载。";
            case COOKIES_MISSING: return "尚未取得网易云音乐登录会话，请先完成登录后再点验证。";
            default: return "正在准备网页登录…";
        }
    }
}
