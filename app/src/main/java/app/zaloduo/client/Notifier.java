package app.zaloduo.client;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.notification.StatusBarNotification;
import android.util.Base64;
import android.util.Log;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Biến lệnh thông báo của Zalo Web thành thông báo Android thật. */
final class Notifier {
    static final int CALL_ID = 2;

    private static final int ZALO_BLUE = 0xFF0068FF;
    private static final Pattern CALL_TEXT = Pattern.compile(
            "cuộc gọi|đang gọi|gọi video|gọi thoại|gọi đến|incoming call|is calling|calling you",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Map<String, Integer> idByJsId = new ConcurrentHashMap<>();
    private static final Set<String> cancelledCalls =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final AtomicInteger seq = new AtomicInteger(1000);

    private Notifier() {}

    static NotificationManager nm(Context c) {
        return c.getSystemService(NotificationManager.class);
    }

    static String permissionState(Context c) {
        if (nm(c).areNotificationsEnabled()) return "granted";
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Prefs.bool(c, Prefs.NOTIF_ASKED, false)) {
            return "default";
        }
        return "denied";
    }

    /** Gọi từ luồng JS bridge khi Zalo Web tạo new Notification(...). */
    static void show(final Context c, final String jsId, String title, String body, String tag,
                     final String iconUrl, final boolean silent) {
        final String t = title == null ? "" : title;
        final String b = body == null ? "" : body;
        final boolean call = Prefs.callAlerts(c) && CALL_TEXT.matcher(t + "\n" + b).find();
        final int nid;
        if (call) nid = CALL_ID;
        else if (tag != null && !tag.isEmpty()) nid = idForTag(tag);
        else nid = nextId();
        // Danh sách chat có thể đã báo "còn chưa đọc" trước khi thông báo này tới (shim chỉ gửi khi danh sách đổi).
        if (!call && tag != null && !tag.isEmpty()) shownConv.put(tag, Boolean.TRUE.equals(lastUnread.get(tag)));
        if (jsId != null) {
            if (idByJsId.size() > 500) idByJsId.clear();
            idByJsId.put(jsId, nid);
        }
        DiagLog.add("android notification posted" + (call ? " (call)" : "")
                + (MainActivity.visible ? " while app open" : ""));
        App.IO.execute(() -> {
            Bitmap icon = loadIcon(iconUrl);
            if (call) {
                if (jsId != null && cancelledCalls.remove(jsId)) return;
                if (MainActivity.visible) return; // đang mở app thì giao diện Zalo tự hiện cuộc gọi
                post(c, nid, buildCall(c, t, b, icon, true));
            } else {
                post(c, nid, buildMessage(c, jsId, t, b, icon, silent, nid));
            }
        });
    }

    /**
     * Zalo Web có thể tự đóng thông báo sau vài giây (thói quen trên PC). Trên điện thoại như vậy
     * sẽ làm lỡ tin nhắn, nên chỉ chấp nhận lệnh đóng với thông báo cuộc gọi (cuộc gọi đã kết thúc).
     */
    static void cancel(Context c, String jsId) {
        if (jsId == null) return;
        Integer nid = idByJsId.remove(jsId);
        if (nid != null && nid == CALL_ID) {
            if (cancelledCalls.size() > 100) cancelledCalls.clear();
            cancelledCalls.add(jsId);
            cancelCallAlert(c);
        }
    }

    /** Phát hiện Zalo đang đổ chuông (âm thanh lặp) khi app ở nền: hiện màn hình cuộc gọi, không thêm chuông. */
    static void showRingAlert(final Context c) {
        if (!Prefs.callAlerts(c) || MainActivity.visible) return;
        post(c, CALL_ID, buildCall(c, c.getString(R.string.call_incoming),
                c.getString(R.string.call_open_hint), null, false));
    }

    static void cancelCallAlert(Context c) {
        nm(c).cancel(CALL_ID);
    }

    /** Mã thông báo Android của một hội thoại: Zalo đặt tag = mã hội thoại, mỗi hội thoại một thông báo (tin mới thay tin cũ). */
    private static int idForTag(String tag) {
        return 0x40000000 | (tag.hashCode() & 0x3FFFFFFF);
    }

    /** Vào một hội thoại (JsBridge.convOpened): xoá thông báo của riêng hội thoại đó, các hội thoại khác giữ nguyên. */
    static void clearConversation(Context c, String convId) {
        if (convId == null || convId.isEmpty()) return;
        int nid = idForTag(convId);
        nm(c).cancel(nid);
        idByJsId.values().removeIf(v -> v == nid);
        shownConv.remove(convId);
    }

    /*
     * Hội thoại đang có thông báo -> đã thấy nó "còn chưa đọc" trong danh sách chat sau khi báo chưa. Đọc ở máy khác
     * (điện thoại, Zalo PC): Zalo Web nhận đồng bộ, số chưa đọc về 0 -> xoá thông báo. Phải thấy "còn chưa đọc" trước,
     * vì danh sách cũ (chưa kịp tăng số chưa đọc) có thể tới ngay sau thông báo mới và làm xoá nhầm.
     */
    private static final java.util.Map<String, Boolean> shownConv = new java.util.concurrent.ConcurrentHashMap<>();
    /** Trạng thái "còn chưa đọc" mới nhất của mỗi hội thoại trong danh sách chat. */
    private static final java.util.Map<String, Boolean> lastUnread = new java.util.concurrent.ConcurrentHashMap<>();

    /** Danh sách chat mới (JsBridge.convList, JSON của shim mục 12): xoá thông báo của hội thoại đã đọc ở nơi khác. */
    static void syncRead(Context c, String json) {
        if (json == null) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                boolean unread = o.optInt("u") > 0 || o.optInt("at") > 0 || o.optInt("ud") > 0;
                lastUnread.put(id, unread);
                Boolean seenUnread = shownConv.get(id);
                if (seenUnread == null) continue;
                if (unread) {
                    if (!seenUnread) shownConv.put(id, true);
                } else if (seenUnread) {
                    shownConv.remove(id);
                    int nid = idForTag(id);
                    nm(c).cancel(nid);
                    idByJsId.values().removeIf(v -> v == nid);
                    DiagLog.add("notification cleared: conversation read on another device");
                }
            }
        } catch (Exception e) {
            Log.w(WebViewHolder.TAG, "syncRead", e);
        }
    }

    static void clearMessages(Context c) {
        try {
            for (StatusBarNotification sbn : nm(c).getActiveNotifications()) {
                int id = sbn.getId();
                if (id != KeepAliveService.NOTIF_ID && id != CALL_ID) nm(c).cancel(sbn.getTag(), id);
            }
        } catch (RuntimeException e) {
            Log.w(WebViewHolder.TAG, "clearMessages", e);
        }
        idByJsId.values().removeIf(v -> v != CALL_ID);
        shownConv.clear();
    }

    static void clearAll(Context c) {
        clearMessages(c);
        cancelCallAlert(c);
    }

    // ------------------------------------------------------------------ dựng thông báo

    private static Notification buildMessage(Context c, String jsId, String title, String body,
                                             Bitmap icon, boolean silent, int nid) {
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CLICK_ID, jsId);
        PendingIntent pi = PendingIntent.getActivity(c, nid, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, silent ? App.CH_MESSAGES_SILENT : App.CH_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setColor(ZALO_BLUE)
                .setContentTitle(title.isEmpty() ? c.getString(R.string.app_name) : title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (icon != null) b.setLargeIcon(icon);
        return b.build();
    }

    private static Notification buildCall(Context c, String title, String body, Bitmap icon, boolean ring) {
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_CALL, true);
        PendingIntent pi = PendingIntent.getActivity(c, CALL_ID, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, ring ? App.CH_CALLS : App.CH_CALLS_QUIET)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setColor(ZALO_BLUE)
                .setContentTitle(title.isEmpty() ? c.getString(R.string.call_incoming) : title)
                .setContentText(body.isEmpty() ? c.getString(R.string.call_open_hint) : body)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(true)
                .setTimeoutAfter(60_000)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(c, R.drawable.ic_stat_call),
                        c.getString(R.string.call_open), pi).build());
        if (icon != null) b.setLargeIcon(icon);
        Notification n = b.build();
        if (ring) n.flags |= Notification.FLAG_INSISTENT; // chuông lặp tới khi mở hoặc hết 60 giây
        return n;
    }

    private static void post(Context c, int id, Notification n) {
        try {
            nm(c).notify(id, n);
        } catch (SecurityException e) {
            Log.w(WebViewHolder.TAG, "No notification permission", e);
        }
    }

    private static int nextId() {
        int v = seq.incrementAndGet();
        if (v > 0x3FFFFFF0) {
            seq.set(1000);
            v = 1000;
        }
        return v;
    }

    // ------------------------------------------------------------------ ảnh đại diện

    private static Bitmap loadIcon(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            Bitmap raw = null;
            if (url.startsWith("data:")) {
                int comma = url.indexOf(',');
                if (comma > 0 && url.substring(0, comma).contains(";base64")) {
                    byte[] bytes = Base64.decode(url.substring(comma + 1), Base64.DEFAULT);
                    raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                }
            } else if (url.startsWith("https://")) {
                HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
                con.setConnectTimeout(4000);
                con.setReadTimeout(4000);
                con.setRequestProperty("User-Agent", WebViewHolder.userAgent);
                try (InputStream in = con.getInputStream()) {
                    raw = BitmapFactory.decodeStream(in);
                } finally {
                    con.disconnect();
                }
            }
            return raw == null ? null : circle(raw, 160);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap circle(Bitmap src, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int s = Math.min(src.getWidth(), src.getHeight());
        int left = (src.getWidth() - s) / 2;
        int top = (src.getHeight() - s) / 2;
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(src, new Rect(left, top, left + s, top + s), new Rect(0, 0, size, size), paint);
        return out;
    }
}
