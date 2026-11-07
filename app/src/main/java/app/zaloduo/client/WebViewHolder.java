package app.zaloduo.client;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Presentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.MutableContextWrapper;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Giữ một WebView Zalo Web duy nhất cho cả tiến trình. WebView sống độc lập với Activity
 * (được dịch vụ chạy nền giữ lại), nên đóng màn hình app vẫn không mất kết nối.
 * Mọi hàm ở đây chỉ được gọi trên luồng chính.
 */
final class WebViewHolder {
    static final String TAG = "ZaloDuo";
    static final String HOME = "https://chat.zalo.me/";

    private static final String[] TRUSTED_DOMAINS = {"zalo.me", "zaloapp.com", "zalo.vn"};
    private static final Set<String> SCRIPT_ORIGINS = new HashSet<>(Arrays.asList(
            "https://zalo.me", "https://*.zalo.me",
            "https://zaloapp.com", "https://*.zaloapp.com",
            "https://zalo.vn", "https://*.zalo.vn"));

    private static Context app;
    private static MutableContextWrapper contextWrapper;
    private static ZaloWebView main;
    private static final List<ZaloWebView> popups = new ArrayList<>();
    private static WeakReference<MainActivity> host = new WeakReference<>(null);
    private static String shimSource;
    private static boolean documentStartSupported;
    private static boolean networkCallbackRegistered;
    private static String failedUrl;
    private static Boolean lastHidden;
    /** UA đang dùng, để tải ảnh đại diện / tệp từ luồng nền với cùng UA. */
    static volatile String userAgent = "Mozilla/5.0";

    private static final Runnable RETRY = WebViewHolder::retryIfFailed;

    private WebViewHolder() {}

    // ------------------------------------------------------------------ vòng đời

    static ZaloWebView get(Context ctx) {
        if (main == null) create(ctx);
        return main;
    }

    static ZaloWebView peek() {
        return main;
    }

    static void attach(MainActivity activity) {
        host = new WeakReference<>(activity);
        get(activity);
        contextWrapper.setBaseContext(activity);
    }

    static void detach(MainActivity activity) {
        // Màn khác (màn chính / bong bóng chat) đã lấy WebView: không gỡ của nó.
        if (host.get() != activity) return;
        host = new WeakReference<>(null);
        if (contextWrapper != null && contextWrapper.getBaseContext() == activity) {
            contextWrapper.setBaseContext(app);
        }
        if (main != null) detachFromParent(main);
        for (ZaloWebView p : popups) detachFromParent(p);
        park();
    }

    // ------------------------------------------------------------------ chỗ đỗ WebView khi không có màn hình

    /*
     * WebView không gắn vào cửa sổ nào (màn hình app bị huỷ do vuốt khỏi đa nhiệm, hoặc app chạy ngầm sau khi bật máy)
     * bị Chromium coi là ẩn và đóng băng trang sau vài phút: Zalo ngừng nhận tin. Khi đó đặt WebView lên một màn hình
     * ảo riêng của app (không cần quyền gì), luôn "đang hiển thị". Vẽ ở nửa độ phân giải, cùng kích thước dp với màn hình
     * thật để Zalo không phải dựng lại bố cục. Mở app thì MainActivity lấy WebView về (unpark).
     */
    private static VirtualDisplay parkDisplay;
    private static ImageReader parkReader;
    private static Presentation parkWindow;
    private static FrameLayout parkFrame;

    static void park() {
        if (main == null || host.get() != null || !Prefs.keepAlive(app)) return;
        if (parkFrame != null && main.getParent() == parkFrame) return;
        try {
            if (parkDisplay == null) {
                DisplayMetrics dm = app.getResources().getDisplayMetrics();
                int w = Math.max(1, dm.widthPixels / 2), h = Math.max(1, dm.heightPixels / 2);
                parkReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
                // Bỏ ngay mọi khung hình vẽ ra, nếu không hàng đợi đầy sẽ chặn luồng vẽ của WebView.
                parkReader.setOnImageAvailableListener(r -> {
                    Image i = r.acquireLatestImage();
                    if (i != null) i.close();
                }, App.MAIN);
                parkDisplay = app.getSystemService(DisplayManager.class).createVirtualDisplay("ZaloDuo",
                        w, h, Math.max(1, dm.densityDpi / 2), parkReader.getSurface(),
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            }
            if (parkWindow == null) {
                parkWindow = new Presentation(app, parkDisplay.getDisplay());
                parkFrame = new FrameLayout(parkWindow.getContext());
                parkWindow.setContentView(parkFrame);
                parkWindow.show();
            }
            detachFromParent(main);
            parkFrame.addView(main, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            DiagLog.sys("webview parked on virtual display");
        } catch (RuntimeException e) {
            Log.w(TAG, "park webview failed", e);
            DiagLog.sys("park webview failed: " + e);
            unpark();
        }
    }

    /** Lấy WebView khỏi màn hình ảo (MainActivity sắp gắn nó vào màn hình thật). */
    static void unpark() {
        if (parkFrame != null && main != null && main.getParent() == parkFrame) parkFrame.removeView(main);
        try {
            if (parkWindow != null) parkWindow.dismiss();
        } catch (RuntimeException ignored) {
        }
        parkWindow = null;
        parkFrame = null;
        if (parkDisplay != null) parkDisplay.release();
        parkDisplay = null;
        if (parkReader != null) parkReader.close();
        parkReader = null;
    }

    static MainActivity host() {
        return host.get();
    }

    private static void create(Context ctx) {
        app = ctx.getApplicationContext();
        if (contextWrapper == null) contextWrapper = new MutableContextWrapper(app);
        MainActivity a = host.get();
        contextWrapper.setBaseContext(a != null ? a : app);
        documentStartSupported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT);
        main = newWebView(false);
        registerNetworkCallback();
        main.loadUrl(HOME);
        park(); // tạo từ dịch vụ chạy nền (chưa có màn hình): đặt lên màn hình ảo
    }

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private static ZaloWebView newWebView(boolean popup) {
        ZaloWebView wv = new ZaloWebView(contextWrapper, popup);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        applyViewport(wv);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setTextZoom(Prefs.textZoom(app));
        userAgent = desktopUserAgent(app);
        s.setUserAgentString(userAgent);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        // Giữ tiến trình hiển thị web ở mức ưu tiên cao kể cả khi không nhìn thấy, để không bị hệ thống giết.
        wv.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        // Màu nền trang lần trước (tối khi Zalo để giao diện tối): khỏi chớp trắng trước khi trang kịp vẽ.
        wv.setBackgroundColor(popup ? Color.WHITE : Prefs.barBottom(app));
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.addJavascriptInterface(new JsBridge(app, wv), "ZaloBridge");
        wv.setWebViewClient(new Client());
        wv.setWebChromeClient(new Chrome());
        wv.setDownloadListener(new Downloads.Listener(app, wv));
        installScript(wv);
        return wv;
    }

    /**
     * Giao diện điện thoại: trang rộng đúng bằng màn hình (Zalo Web tự chuyển sang bố cục hẹp), bất kể thẻ
     * viewport của trang và kể cả khi shim.js chạy trễ. Giao diện máy tính: bề rộng theo thẻ viewport (shim.js ép).
     */
    private static void applyViewport(ZaloWebView wv) {
        boolean wide = wv.popup || !Prefs.mobileUi(app);
        WebSettings s = wv.getSettings();
        s.setUseWideViewPort(wide);
        s.setLoadWithOverviewMode(wide);
    }

    /** UA của Chrome trên Windows, cùng số phiên bản Chrome với WebView đang chạy. */
    static String desktopUserAgent(Context ctx) {
        String major = "130";
        try {
            Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(WebSettings.getDefaultUserAgent(ctx));
            if (m.find()) major = m.group(1);
        } catch (RuntimeException ignored) {
        }
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + major + ".0.0.0 Safari/537.36";
    }

    // ------------------------------------------------------------------ script chèn vào trang

    private static void installScript(ZaloWebView wv) {
        if (!documentStartSupported) return;
        if (wv.scriptHandler != null) {
            wv.scriptHandler.remove();
            wv.scriptHandler = null;
        }
        try {
            wv.scriptHandler = WebViewCompat.addDocumentStartJavaScript(wv, buildScript(), SCRIPT_ORIGINS);
        } catch (RuntimeException e) {
            Log.w(TAG, "addDocumentStartJavaScript failed, using fallback injection", e);
            documentStartSupported = false;
        }
    }

    /** Mã bí mật của tiến trình, shim.js gửi kèm mọi lời gọi ZaloBridge (xem JsBridge.ok). Đổi mỗi lần app chạy. */
    static final String BRIDGE_TOKEN = newToken();

    private static String newToken() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static String buildScript() {
        if (shimSource == null) shimSource = readAsset(app, "shim.js");
        // Chỉ gán cấu hình khi shim chưa chạy: lần chạy lại (lưới an toàn ở onPageFinished) shim tự bỏ qua nên sẽ không
        // gỡ __ZW_CFG (có mã bí mật BRIDGE_TOKEN) khỏi trang.
        return "if(!window.__zw)window.__ZW_CFG={bt:\"" + BRIDGE_TOKEN + "\",viewportWidth:" + Prefs.viewportWidth(app)
                + ",ringDetect:" + Prefs.callAlerts(app)
                + ",mobile:" + Prefs.mobileUi(app)
                + ",dockOpacity:" + Prefs.dockOpacity(app)
                + ",dockAutoHide:" + Prefs.dockAutoHide(app)
                + ",dockBottom:" + Prefs.dockBottom(app)
                + ",dockCloud:" + Prefs.dockCloud(app)
                + ",dockTools:" + Prefs.dockTools(app)
                + ",nativeList:" + (Prefs.mobileUi(app) && Prefs.nativeList(app)) + "};\n" + shimSource;
    }

    /** Hiện / ẩn nút zCloud, Công cụ bên phải dock: ngay trên trang đang mở, và cho lần tải trang sau. */
    static void applyDockButtons() {
        if (main == null) return;
        installScript(main);
        main.evaluateJavascript("window.__zw&&window.__zw.setDockButtons&&window.__zw.setDockButtons("
                + Prefs.dockCloud(app) + "," + Prefs.dockTools(app) + ")", null);
    }

    /** Áp cài đặt dock (độ đậm, tự ẩn, khoảng cách đáy) ngay trên trang đang mở, và cho lần tải trang sau. */
    static void applyDockSettings() {
        if (main == null) return;
        installScript(main);
        main.evaluateJavascript("window.__zw&&window.__zw.setDock&&window.__zw.setDock("
                + Prefs.dockOpacity(app) + "," + Prefs.dockAutoHide(app) + "," + Prefs.dockBottom(app) + ")", null);
    }

    private static String readAsset(Context ctx, String name) {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + name, e);
        }
    }

    // ------------------------------------------------------------------ trạng thái ẩn/hiện

    /** Báo cho Zalo Web biết app đang ở nền (để nó bắn thông báo) hay đang được xem. */
    static void setHidden(boolean hidden) {
        lastHidden = hidden;
        String js = "window.__zw&&window.__zw.setHidden(" + hidden + ")";
        if (main != null) main.evaluateJavascript(js, null);
        for (ZaloWebView p : popups) p.evaluateJavascript(js, null);
    }

    /** Điện thoại vừa tỉnh / có mạng lại / mở app: shim.js kiểm tra kết nối của Zalo và nối lại nếu đã đứt. */
    static void wake(String reason) { // reason: chuỗi cố định trong app, không chứa dấu nháy
        evalMain("window.__zw&&window.__zw.wake&&window.__zw.wake('" + reason + "')");
    }

    static void evalMain(final String js) {
        App.MAIN.post(() -> {
            if (main != null) main.evaluateJavascript(js, null);
        });
    }

    /** Khung chat đang mở toàn màn hình (giao diện điện thoại) — Back sẽ quay về danh sách. */
    static volatile boolean mobileChatOpen;

    /** Trang Zalo đã hiện danh sách chat (shim.js báo); false trong lúc đang tải / đăng nhập. */
    static boolean pageReady;

    /** Lấy cấu trúc khung giao diện Zalo (không có nội dung tin nhắn) để gửi cho người sửa lỗi. */
    static void dumpLayout(final ValueCallback<String> done) {
        if (main == null) {
            done.onReceiveValue(null);
            return;
        }
        main.evaluateJavascript(readAsset(app, "dump.js"), value -> done.onReceiveValue(decodeJsString(value)));
    }

    /** Trạng thái ngắn cho màn hình Cài đặt: phiên bản script, đã nhận dạng bố cục chưa. */
    static void scriptStatus(final ValueCallback<String> done) {
        if (main == null) {
            done.onReceiveValue(null);
            return;
        }
        main.evaluateJavascript("(function(){var z=window.__zw;if(!z)return 'none';"
                + "return 'v'+(z.version||'?')+'|'+(z.mobileInfo?z.mobileInfo():'{}');})()",
                value -> done.onReceiveValue(decodeJsString(value)));
    }

    /** Trạng thái trang Zalo lúc này cho log debug (không có tên người hay nội dung tin nhắn); null nếu chưa mở. */
    static void debugState(final ValueCallback<String> done) {
        if (main == null) {
            done.onReceiveValue(null);
            return;
        }
        main.evaluateJavascript("(function(){var z=window.__zw;if(!z)return 'script: not running';var d=document;"
                + "return 'script v'+(z.version||'?')+' | layout '+(z.mobileInfo?z.mobileInfo():'?')"
                + "+' | ws '+(z.wsInfo?z.wsInfo():'?')+' | logged in '+!!d.getElementById('sidebarNav')"
                + "+' | online '+navigator.onLine+' | hidden '+d.hidden+' | html.'+d.documentElement.className"
                + "+' | url '+location.host+location.pathname+' | viewport '+innerWidth+'x'+innerHeight;})()",
                value -> done.onReceiveValue(decodeJsString(value)));
    }

    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return null;
        try {
            return new org.json.JSONArray("[" + value + "]").getString(0);
        } catch (org.json.JSONException e) {
            return value;
        }
    }

    // ------------------------------------------------------------------ popup (window.open)

    static ZaloWebView createPopup() {
        final ZaloWebView p = newWebView(true);
        popups.add(p);
        // Popup không bao giờ hiện trang Zalo nào (ví dụ chỉ mở link ngoài) thì dọn đi.
        App.MAIN.postDelayed(() -> {
            if (!p.shown && popups.contains(p)) closePopup(p);
        }, 30_000);
        return p;
    }

    static void showPopup(ZaloWebView p) {
        if (p.shown || !popups.contains(p)) return;
        p.shown = true;
        MainActivity a = host.get();
        if (a != null) a.showPopup(p);
    }

    static void closePopup(WebView w) {
        if (!(w instanceof ZaloWebView) || !popups.remove(w)) return;
        detachFromParent(w);
        w.destroy();
        MainActivity a = host.get();
        if (a != null) a.onPopupsChanged();
    }

    private static void closePopupIfUnused(final WebView view) {
        if (view instanceof ZaloWebView && ((ZaloWebView) view).popup && !((ZaloWebView) view).shown) {
            App.MAIN.post(() -> closePopup(view));
        }
    }

    static ZaloWebView topPopup() {
        for (int i = popups.size() - 1; i >= 0; i--) {
            if (popups.get(i).shown) return popups.get(i);
        }
        return null;
    }

    static List<ZaloWebView> shownPopups() {
        List<ZaloWebView> out = new ArrayList<>();
        for (ZaloWebView p : popups) if (p.shown) out.add(p);
        return out;
    }

    static void detachFromParent(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    // ------------------------------------------------------------------ cài đặt, tải lại, đăng xuất

    static void applySettings(boolean reload) {
        if (main == null) return;
        int zoom = Prefs.textZoom(app);
        main.getSettings().setTextZoom(zoom);
        for (ZaloWebView p : popups) p.getSettings().setTextZoom(zoom);
        if (reload) {
            applyViewport(main);
            installScript(main);
            for (ZaloWebView p : popups) installScript(p);
            reload();
        }
    }

    static void reload() {
        if (main == null) return;
        if (isTrustedUrl(main.getUrl())) main.reload();
        else main.loadUrl(HOME);
    }

    static void resetAll(Context ctx, final Runnable done) {
        final Context appCtx = ctx.getApplicationContext();
        final CookieManager cm = CookieManager.getInstance();
        cm.removeAllCookies(ok -> {
            cm.flush();
            WebStorage.getInstance().deleteAllData();
            for (ZaloWebView p : new ArrayList<>(popups)) closePopup(p);
            if (main != null) {
                main.clearCache(true);
                main.loadUrl(HOME);
                main.clearHistory();
            }
            Notifier.clearAll(appCtx);
            if (done != null) done.run();
        });
    }

    static void flushCookies() {
        CookieManager.getInstance().flush();
    }

    // ------------------------------------------------------------------ điều hướng

    static boolean isTrustedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        for (String d : TRUSTED_DOMAINS) {
            if (h.equals(d) || h.endsWith("." + d)) return true;
        }
        return false;
    }

    static boolean isTrustedUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        return isTrustedHost(Uri.parse(url).getHost());
    }

    static boolean isHome(String url) {
        return url != null && url.startsWith(HOME);
    }

    /** @return true nếu đã tự xử lý (mở ra ngoài), false để WebView tự tải. */
    private static boolean handleNavigation(WebView view, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "https":
                if (isTrustedHost(uri.getHost())) return false;
                openExternal(uri);
                return true;
            case "http":
                if (isTrustedHost(uri.getHost())) {
                    view.loadUrl(uri.buildUpon().scheme("https").build().toString());
                } else {
                    openExternal(uri);
                }
                return true;
            case "":
            case "about":
            case "data":
            case "blob":
            case "javascript":
                return false;
            case "intent":
                openIntentUri(uri.toString());
                return true;
            default:
                openExternal(uri);
                return true;
        }
    }

    static void openExternal(Uri uri) {
        Intent i = new Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast(app.getString(R.string.no_app_to_open));
        }
    }

    private static void openIntentUri(String s) {
        try {
            Intent i = Intent.parseUri(s, Intent.URI_INTENT_SCHEME);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            i.setComponent(null);
            i.setSelector(null);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                app.startActivity(i);
                return;
            } catch (ActivityNotFoundException ignored) {
            }
            String fallback = i.getStringExtra("browser_fallback_url");
            if (fallback != null) openExternal(Uri.parse(fallback));
        } catch (URISyntaxException ignored) {
        }
    }

    static void toast(final String text) {
        App.MAIN.post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }

    // ------------------------------------------------------------------ mạng & sự cố

    private static void registerNetworkCallback() {
        if (networkCallbackRegistered) return;
        networkCallbackRegistered = true;
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    DiagLog.sys("network available");
                    App.MAIN.post(RETRY);
                    wake("network");
                }

                @Override
                public void onLost(Network network) {
                    DiagLog.sys("network lost");
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", e);
        }
    }

    private static void scheduleRetry() {
        App.MAIN.removeCallbacks(RETRY);
        App.MAIN.postDelayed(RETRY, 15_000);
    }

    private static void retryIfFailed() {
        if (failedUrl != null && main != null) {
            String u = failedUrl;
            failedUrl = null;
            main.loadUrl(u);
        }
    }

    /** Tiến trình hiển thị web bị hệ thống giết: dựng lại WebView thay vì để app crash. */
    private static void onRendererGone(WebView view) {
        if (view != main) {
            closePopup(view);
            return;
        }
        for (ZaloWebView p : new ArrayList<>(popups)) closePopup(p);
        detachFromParent(main);
        main.destroy();
        main = null;
        create(app);
        MainActivity a = host.get();
        if (a != null) a.onMainWebViewReplaced(main);
    }

    // ------------------------------------------------------------------ quyền mic/camera cho trang

    static void handlePermissionRequest(PermissionRequest req) {
        Uri origin = req.getOrigin();
        if (origin == null || !"https".equals(origin.getScheme()) || !isTrustedHost(origin.getHost())) {
            req.deny();
            return;
        }
        List<String> missing = missingPermissions(req);
        MainActivity a = host.get();
        if (missing.isEmpty() || a == null) {
            grantAvailable(req);
        } else {
            a.requestWebPermissions(req, missing);
        }
    }

    private static List<String> missingPermissions(PermissionRequest req) {
        List<String> out = new ArrayList<>();
        for (String r : req.getResources()) {
            String perm = androidPermissionFor(r);
            if (perm != null && app.checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED
                    && !out.contains(perm)) {
                out.add(perm);
            }
        }
        return out;
    }

    static void grantAvailable(PermissionRequest req) {
        List<String> ok = new ArrayList<>();
        for (String r : req.getResources()) {
            String perm = androidPermissionFor(r);
            if (perm != null && app.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) ok.add(r);
        }
        if (ok.isEmpty()) req.deny();
        else req.grant(ok.toArray(new String[0]));
    }

    private static String androidPermissionFor(String resource) {
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) return Manifest.permission.RECORD_AUDIO;
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return Manifest.permission.CAMERA;
        return null;
    }

    // ------------------------------------------------------------------ WebViewClient

    private static final class Client extends WebViewClient {
        /** Ảnh / video của Zalo: trả từ bộ nhớ của app nếu đã có, chưa có thì tải và lưu lại (MediaCache). */
        @Override
        public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            return MediaCache.intercept(view.getContext(), request);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) return false;
            boolean handled = handleNavigation(view, request.getUrl());
            if (handled) closePopupIfUnused(view);
            return handled;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            ZaloWebView z = (ZaloWebView) view;
            z.lastUrl = url == null ? "" : url;
            if (view == main) {
                mobileChatOpen = false;
                pageReady = false;
                DiagLog.sys("page load " + (isTrustedUrl(url) ? "zalo" : "other site"));
                // Trang tải lại khi app đang mở (nối lại sau khi tỉnh, renderer bị tắt...): che bằng ảnh chụp như lúc mở app.
                MainActivity a = host();
                // Trang đăng nhập của Zalo (id.zalo.me...) không phải trang chat: hiện cho người dùng, không che.
                boolean chat = url != null && Uri.parse(url).getHost() != null && Uri.parse(url).getHost().startsWith("chat.");
                if (a != null) {
                    if (chat) a.onPageReload();
                    else a.onLoginPage();
                }
            }
            if (z.popup && url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                if (isTrustedUrl(url)) {
                    showPopup(z);
                } else {
                    // Một số bản WebView không gọi shouldOverrideUrlLoading cho trang đầu của popup.
                    view.stopLoading();
                    openExternal(Uri.parse(url));
                    closePopupIfUnused(view);
                    return;
                }
            }
            if (!documentStartSupported && isTrustedUrl(url)) view.evaluateJavascript(buildScript(), null);
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            ((ZaloWebView) view).lastUrl = url == null ? "" : url;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            ((ZaloWebView) view).lastUrl = url == null ? "" : url;
            if (isTrustedUrl(url)) {
                // Lưới an toàn cả khi có addDocumentStartJavaScript: shim.js tự bỏ qua nếu đã chạy.
                view.evaluateJavascript(buildScript(), null);
                if (view == main) scriptStatus(v -> Log.d(TAG, "script status: " + v));
            }
            if (lastHidden != null) {
                view.evaluateJavascript("window.__zw&&window.__zw.setHidden(" + lastHidden + ")", null);
            }
            flushCookies();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && view == main) {
                failedUrl = request.getUrl().toString();
                scheduleRetry();
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }

        @Override
        public boolean onRenderProcessGone(final WebView view, RenderProcessGoneDetail detail) {
            DiagLog.sys("webview renderer gone (" + (detail.didCrash() ? "crashed" : "killed by system") + ")");
            App.MAIN.post(() -> onRendererGone(view));
            return true;
        }
    }

    // ------------------------------------------------------------------ WebChromeClient

    private static final class Chrome extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            ZaloWebView popup = createPopup();
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            if (window != main) closePopup(window);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            handlePermissionRequest(request);
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            MainActivity a = host.get();
            if (a != null) a.onWebPermissionCanceled(request);
        }

        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            MainActivity a = host.get();
            return a != null && a.showFileChooser(callback, params);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            MainActivity a = host.get();
            if (a != null) a.showCustomView(view, callback);
            else callback.onCustomViewHidden();
        }

        @Override
        public void onHideCustomView() {
            MainActivity a = host.get();
            if (a != null) a.hideCustomView();
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            // Bỏ biểu tượng "play" xám mặc định trên các khung video (ví dụ khi gọi video).
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        }

        // Hộp thoại JS chỉ hiện được khi có màn hình; đang chạy nền thì bỏ qua.
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            return cancelIfHeadless(result);
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
            return cancelIfHeadless(result);
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  JsPromptResult result) {
            return cancelIfHeadless(result);
        }

        @Override
        public boolean onJsBeforeUnload(WebView view, String url, String message, JsResult result) {
            return cancelIfHeadless(result);
        }

        private boolean cancelIfHeadless(JsResult result) {
            if (host.get() != null) return false;
            result.cancel();
            return true;
        }

        @Override
        public boolean onConsoleMessage(ConsoleMessage m) {
            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                Log.d(TAG, "console: " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
            }
            return true;
        }
    }
}
