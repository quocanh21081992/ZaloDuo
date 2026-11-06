package app.zaloduo.client;

import android.content.Context;
import android.content.SharedPreferences;

/** Cài đặt người dùng, lưu trong SharedPreferences. */
final class Prefs {
    static final String KEEP_ALIVE = "keep_alive";
    static final String START_ON_BOOT = "start_on_boot";
    static final String CALL_ALERTS = "call_alerts";
    static final String MOBILE_UI = "mobile_ui";
    static final String VIEWPORT_WIDTH = "viewport_width";
    static final String TEXT_ZOOM = "text_zoom";
    static final String NOTIF_ASKED = "notif_asked";
    static final String BATTERY_ASKED = "battery_asked";

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("zaloduo", Context.MODE_PRIVATE);
    }

    static boolean bool(Context c, String key, boolean def) {
        return sp(c).getBoolean(key, def);
    }

    static int integer(Context c, String key, int def) {
        return sp(c).getInt(key, def);
    }

    static void put(Context c, String key, boolean value) {
        sp(c).edit().putBoolean(key, value).apply();
    }

    static void put(Context c, String key, int value) {
        sp(c).edit().putInt(key, value).apply();
    }

    static boolean keepAlive(Context c) { return bool(c, KEEP_ALIVE, true); }

    static boolean startOnBoot(Context c) { return bool(c, START_ON_BOOT, true); }

    /** Zalo Web không nghe gọi được: báo cuộc gọi đến (đổ chuông, toàn màn hình) đã bỏ. */
    static boolean callAlerts(Context c) { return false; }

    static final String INCOMING_CALLS = "incoming_calls";
    /** Nhận cuộc gọi đến bằng app: nâng phiên lên kiểu Zalo PC để máy chủ gửi lời mời (CallManager). */
    static boolean incomingCalls(Context c) { return bool(c, INCOMING_CALLS, true); }

    static final String RINGTONE = "call_ringtone";

    /** Nhạc chuông cuộc gọi đến: chưa chọn = nhạc chuông mặc định của máy; chọn "Im lặng" = null (chỉ rung). */
    static android.net.Uri ringtone(Context c) {
        String s = sp(c).getString(RINGTONE, null);
        if (s == null) return android.provider.Settings.System.DEFAULT_RINGTONE_URI;
        return s.isEmpty() ? null : android.net.Uri.parse(s);
    }

    static boolean hasCustomRingtone(Context c) {
        String s = sp(c).getString(RINGTONE, null);
        return s != null && !s.isEmpty() && !android.provider.Settings.System.DEFAULT_RINGTONE_URI.toString().equals(s);
    }

    /** null = im lặng; Uri mặc định của hệ thống = theo máy. */
    static void setRingtone(Context c, android.net.Uri u) {
        if (u != null && android.provider.Settings.System.DEFAULT_RINGTONE_URI.equals(u)) sp(c).edit().remove(RINGTONE).apply();
        else sp(c).edit().putString(RINGTONE, u == null ? "" : u.toString()).apply();
    }

    static final String DOCK_CLOUD = "dock_cloud", DOCK_TOOLS = "dock_tools";
    /** Nút zCloud / Công cụ của Zalo ở bên phải thanh dock (sau My Documents). */
    static boolean dockCloud(Context c) { return bool(c, DOCK_CLOUD, false); }
    static boolean dockTools(Context c) { return bool(c, DOCK_TOOLS, false); }


    /** Giao diện điện thoại: danh sách chat -> chạm mở chat -> Back quay lại. */
    static boolean mobileUi(Context c) { return bool(c, MOBILE_UI, true); }

    /** 0 = theo bề rộng màn hình điện thoại; khác 0 = ép trang hiển thị rộng bấy nhiêu px CSS. */
    static int viewportWidth(Context c) { return integer(c, VIEWPORT_WIDTH, 980); }

    /** Cỡ chữ (%) cho trang Zalo và giao diện native; lựa chọn "Theo máy" (lưu 0) = cỡ chữ trong Cài đặt của Android. */
    static int textZoom(Context c) {
        int v = textZoomSetting(c);
        if (v != 0) return v;
        float s = c.getResources().getConfiguration().fontScale;
        return Math.max(50, Math.min(200, Math.round(s * 100)));
    }

    /** Giá trị đã chọn trong Cài đặt: phần trăm, hoặc 0 = theo cỡ chữ của máy. */
    static int textZoomSetting(Context c) { return integer(c, TEXT_ZOOM, 100); }

    /** Độ đậm nền dock kính mờ (giao diện điện thoại), 0-100%: 0 = chỉ còn lớp làm mờ, 100 = nền đặc. */
    static final String DOCK_OPACITY = "dock_opacity";

    static int dockOpacity(Context c) { return integer(c, DOCK_OPACITY, 60); }

    /** Tự ẩn dock khi cuộn danh sách xuống, hiện lại khi cuộn lên. */
    static final String DOCK_AUTO_HIDE = "dock_auto_hide";

    static boolean dockAutoHide(Context c) { return bool(c, DOCK_AUTO_HIDE, true); }

    /** Danh sách chat vẽ bằng giao diện Android (NativeChatList) thay cho danh sách của Zalo Web. */
    static final String NATIVE_LIST = "native_list";

    static boolean nativeList(Context c) { return bool(c, NATIVE_LIST, true); }

    /** Khoảng cách từ dock tới đáy màn hình, px CSS. */
    static final String DOCK_BOTTOM = "dock_bottom";

    static int dockBottom(Context c) { return integer(c, DOCK_BOTTOM, 24); }

    /** Màu nền trang Zalo ở mép trên/dưới lần gần nhất (sáng/tối), để mở app không bị chớp trắng. */
    static final String BAR_TOP = "bar_top";
    static final String BAR_BOTTOM = "bar_bottom";

    static int barTop(Context c) { return integer(c, BAR_TOP, 0xFFFFFFFF); }

    static int barBottom(Context c) { return integer(c, BAR_BOTTOM, 0xFFFFFFFF); }
}
