package app.zaloduo.client;

import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Cầu nối JS -> Android, lộ ra trong trang dưới tên window.ZaloBridge. Chỉ shim.js gọi tới.
 * Các hàm @JavascriptInterface chạy trên luồng riêng của WebView, không phải luồng chính.
 */
final class JsBridge {
    private final Context app;
    private final ZaloWebView owner;

    /** Các lời gọi Notification.requestPermission() đang chờ người dùng trả lời hộp thoại quyền. */
    private static final List<Object[]> pendingPermission = new ArrayList<>();

    JsBridge(Context app, ZaloWebView owner) {
        this.app = app.getApplicationContext();
        this.owner = owner;
    }

    /** Chỉ phục vụ trang Zalo (https://*.zalo.me ...). Trang khác lỡ được mở cũng không gọi được. */
    private boolean trusted() {
        return WebViewHolder.isTrustedUrl(owner.lastUrl);
    }

    /**
     * addJavascriptInterface lộ ZaloBridge cho mọi khung của trang, kể cả iframe của bên thứ ba nhúng trong trang Zalo
     * (trusted() chỉ xét khung chính). Mỗi lời gọi phải kèm mã bí mật của tiến trình (WebViewHolder.BRIDGE_TOKEN) — chỉ
     * shim.js chạy trong khung của Zalo có mã; iframe khác nguồn không đọc được biến của trang nên không gọi được.
     */
    private boolean ok(String t) {
        return trusted() && WebViewHolder.BRIDGE_TOKEN.equals(t);
    }

    @JavascriptInterface
    public void showNotification(String t, String id, String title, String body, String tag, String icon, boolean silent) {
        if (!ok(t)) return;
        Notifier.show(app, id, title, body, tag, icon, silent);
    }

    @JavascriptInterface
    public void cancelNotification(String t, String id, String tag) {
        if (!ok(t)) return;
        Notifier.cancel(app, id);
    }

    @JavascriptInterface
    public String permission(String t) {
        if (!ok(t)) return "default";
        return Notifier.permissionState(app);
    }

    @JavascriptInterface
    public void requestPermission(String t, final String callbackId) {
        if (!ok(t)) return;
        App.MAIN.post(() -> {
            String state = Notifier.permissionState(app);
            MainActivity a = WebViewHolder.host();
            if (!"default".equals(state) || a == null || Build.VERSION.SDK_INT < 33) {
                deliverPermission(owner, callbackId, state);
                return;
            }
            synchronized (pendingPermission) {
                pendingPermission.add(new Object[]{owner, callbackId});
            }
            a.requestNotificationPermission();
        });
    }

    /** Gọi từ MainActivity sau khi người dùng trả lời hộp thoại quyền thông báo. */
    static void flushPermissionCallbacks(Context ctx) {
        String state = Notifier.permissionState(ctx);
        List<Object[]> copy;
        synchronized (pendingPermission) {
            copy = new ArrayList<>(pendingPermission);
            pendingPermission.clear();
        }
        for (Object[] p : copy) deliverPermission((WebView) p[0], (String) p[1], state);
    }

    private static void deliverPermission(WebView wv, String callbackId, String state) {
        wv.evaluateJavascript("window.__zw&&window.__zw.permResult(" + JSONObject.quote(callbackId) + ","
                + JSONObject.quote(state) + ")", null);
    }

    @JavascriptInterface
    public boolean isHidden(String t) {
        if (!ok(t)) return false;
        return !MainActivity.visible;
    }

    @JavascriptInterface
    public void mediaActive(String t, final boolean active, final boolean video) {
        if (!ok(t)) return;
        App.MAIN.post(() -> KeepAliveService.setMediaActive(app, active, video));
    }

    @JavascriptInterface
    public void ringing(String t, boolean on) {
        if (!ok(t)) return;
        if (on) Notifier.showRingAlert(app);
        else Notifier.cancelCallAlert(app);
    }

    /** Giao diện điện thoại: true = đang mở khung chat, false = đang ở danh sách chat. */
    @JavascriptInterface
    public void mobileState(String t, boolean chatOpen) {
        if (!ok(t) || owner.popup) return;
        WebViewHolder.mobileChatOpen = chatOpen;
    }

    /** Danh sách hội thoại (JSON, mục 12 của shim.js) cho danh sách chat native; app đang ở nền thì chỉ lưu lại. */
    @JavascriptInterface
    public void convList(String t, final String json) {
        if (!ok(t) || owner.popup || json == null) return;
        App.MAIN.post(() -> {
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onConvList(json);
            else NativeChatList.save(app, json);
        });
    }

    /** Danh sách chat native bật/tắt, vị trí thanh trên + dock, màu nền (JSON, mục 12 của shim.js). */
    @JavascriptInterface
    public void nativeLayout(String t, final String json) {
        if (!ok(t) || owner.popup || json == null) return;
        App.MAIN.post(() -> {
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onNativeLayout(json, owner);
        });
    }

    /** Trạng thái dock (JSON, mục 8 của shim.js) cho dock native (NativeDock). */
    @JavascriptInterface
    public void dockState(String t, final String json) {
        if (!ok(t) || owner.popup || json == null) return;
        App.MAIN.post(() -> {
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onDockState(json);
        });
    }

    /** Tải trước ảnh HD / video của các tin vừa nạp (JSON mảng link, mục 15 của shim.js) xuống bộ nhớ của app. */
    @JavascriptInterface
    public void prefetch(String t, final String json) {
        if (!ok(t) || json == null) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (int i = 0; i < a.length() && i < 200; i++) urls.add(a.optString(i));
            MediaCache.prefetch(app, urls);
        } catch (org.json.JSONException ignored) {
        }
    }

    /** Người dùng vừa vào một hội thoại (mục 8 của shim.js): xoá thông báo của hội thoại đó. */
    @JavascriptInterface
    public void convOpened(String t, final String convId) {
        if (!ok(t) || owner.popup || convId == null) return;
        Notifier.clearConversation(app, convId);
    }

    /**
     * Ô nhập tin vừa bị Zalo bỏ focus rồi focus lại (chọn tên ở danh sách @nhắc tên, mục 18 của shim.js): bàn phím
     * Android đã đóng theo, focus bằng script không tự mở lại — mở lại từ phía app.
     */
    @JavascriptInterface
    public void showKeyboard(String t) {
        if (!ok(t)) return;
        App.MAIN.post(() -> {
            owner.requestFocus();
            android.view.inputmethod.InputMethodManager im = app.getSystemService(android.view.inputmethod.InputMethodManager.class);
            if (im != null) im.showSoftInput(owner, 0);
        });
    }

    /** Trạng thái kết nối máy chủ Zalo ("on" / "wait" / "off", mục 11 của shim.js): hiện ở thông báo chạy nền. */
    @JavascriptInterface
    public void connState(String t, final String state) {
        if (!ok(t) || owner.popup || state == null) return;
        App.MAIN.post(() -> {
            KeepAliveService.setConn(state);
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onConnState(state); // nhãn dưới tên trên thanh trên native
        });
    }

    /** Trang đang ở màn đăng nhập (mã QR...): phải hiện trang cho người dùng, bỏ lớp che. */
    @JavascriptInterface
    public void needLogin(String t) {
        if (!ok(t) || owner.popup) return;
        App.MAIN.post(() -> {
            DiagLog.sys("zalo login screen");
            MainActivity a = WebViewHolder.host();
            if (a != null) a.onLoginPage();
        });
    }

    /** Danh sách chat thật đã hiện: bỏ ảnh chụp che màn hình đăng nhập. */
    @JavascriptInterface
    public void pageReady(String t) {
        if (!ok(t) || owner.popup) return;
        App.MAIN.post(() -> {
            if (!WebViewHolder.pageReady) {
                DiagLog.add("chat list shown");
                CallManager.get().onPageLoaded(); // trang mới tải: getLoginInfo của trang đưa phiên về kiểu Web
            }
            WebViewHolder.pageReady = true;
            MainActivity a = WebViewHolder.host();
            if (a != null) a.hideSnapshot();
        });
    }

    /** Màu nền trang ở mép trên/dưới (#rrggbb): tô thanh trạng thái và thanh điều hướng cho khớp. */
    @JavascriptInterface
    public void systemBars(String t, String top, String bottom) {
        if (!ok(t) || owner.popup) return;
        final int ct, cb;
        try {
            ct = Color.parseColor(top);
            cb = Color.parseColor(bottom);
        } catch (IllegalArgumentException | NullPointerException e) {
            return;
        }
        App.MAIN.post(() -> MainActivity.setSystemBarColors(app, ct, cb));
    }

    @JavascriptInterface
    public void saveFile(String t, String name, String mime, String base64) {
        if (!ok(t)) return;
        Downloads.saveBase64(app, name, mime, base64);
    }

    /** Chia sẻ tệp (ảnh đang xem) sang app khác qua bảng chia sẻ của Android. */
    @JavascriptInterface
    public void shareFile(String t, String name, String mime, String base64) {
        if (!ok(t) || base64 == null) return;
        App.MAIN.post(() -> ShareFiles.share(app, name, mime, base64));
    }

    /** Sự kiện từ shim.js cho nhật ký chẩn đoán (không có nội dung tin nhắn). */
    @JavascriptInterface
    public void diag(String t, String event) {
        if (!ok(t) || owner.popup || event == null) return;
        DiagLog.add("web: " + (event.length() > 160 ? event.substring(0, 160) : event));
    }

    /** Sự kiện kết nối máy chủ Zalo từ shim.js (mục 11) cho nhật ký kết nối. */
    @JavascriptInterface
    public void connLog(String t, String event) {
        if (!ok(t) || owner.popup || event == null) return;
        DiagLog.conn("web: " + (event.length() > 240 ? event.substring(0, 240) : event));
    }

    /** Khung điều khiển cuộc gọi đã giải mã từ WebSocket của Zalo (shim mục 19). */
    @JavascriptInterface
    public void callEvent(String t, String dir, int ws, int cmd, int sub, String body) {
        if (!ok(t) || owner.popup || body == null) return;
        CallEvents.onFrame(dir, ws, cmd, sub, body.length() > 20000 ? body.substring(0, 20000) : body);
    }

    /** Kết quả báo hiệu cuộc gọi (window.__zw.callSig). */
    @JavascriptInterface
    public void callSigResult(String t, String reqId, boolean ok, String json) {
        if (!ok(t) || owner.popup || reqId == null) return;
        CallSignal.onResult(reqId, ok, json == null ? "{}" : json);
    }

    /** Người dùng bấm gọi trong trang (shim): mở màn hình cuộc gọi và quay số. */
    @JavascriptInterface
    public void startCall(String t, final String uid, final String name, final String avatar, final boolean video) {
        if (!ok(t) || owner.popup || uid == null || uid.isEmpty()) return;
        App.MAIN.post(() -> CallActivity.dial(app, uid, name, avatar, video));
    }

    /** Người dùng bấm gọi trong nhóm (shim): gọi thoại nhóm. members: JSON mảng uid thành viên (trừ mình). */
    @JavascriptInterface
    public void startGroupCall(String t, final String gid, final String name, final String avatar, final String members,
                               final String me) {
        if (!ok(t) || owner.popup || gid == null || gid.isEmpty()) return;
        App.MAIN.post(() -> CallActivity.dialGroup(app, gid, name, avatar, members));
    }

    @JavascriptInterface
    public void log(String t, String message) {
        if (!ok(t)) return;
        Log.d(WebViewHolder.TAG, "js: " + message);
    }
}
