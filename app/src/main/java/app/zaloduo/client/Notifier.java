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
                post(c, nid, buildMessage(c, jsId, t, b, icon, silent, nid, tag));
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
        cancelKeepBubble(c, nid);
        idByJsId.values().removeIf(v -> v == nid);
        shownConv.remove(convId);
    }

    /**
     * Xoá thông báo của một hội thoại, trừ khi nó đang là bong bóng chat: huỷ thông báo thì bong bóng (có thể đang mở,
     * chính là nơi người dùng đang đọc) cũng mất. Khi đó đăng lại thông báo đó ở dạng chỉ còn bong bóng, không kêu.
     */
    private static void cancelKeepBubble(Context c, int nid) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                for (StatusBarNotification sbn : nm(c).getActiveNotifications()) {
                    if (sbn.getId() != nid) continue;
                    Notification n = sbn.getNotification();
                    Notification.BubbleMetadata bm = n.getBubbleMetadata();
                    if (bm == null || (n.flags & Notification.FLAG_BUBBLE) == 0) break;
                    if (bm.isNotificationSuppressed()) return; // đã chỉ còn bong bóng
                    Notification.Builder b = Notification.Builder.recoverBuilder(c, n)
                            .setOnlyAlertOnce(true)
                            .setBubbleMetadata(copyBubble(bm, true));
                    post(c, nid, b.build());
                    return;
                }
            } catch (RuntimeException e) {
                Log.w(WebViewHolder.TAG, "keep bubble", e);
            }
        }
        nm(c).cancel(nid);
    }

    @android.annotation.TargetApi(30)
    private static Notification.BubbleMetadata copyBubble(Notification.BubbleMetadata bm, boolean suppress) {
        Notification.BubbleMetadata.Builder b = bm.getShortcutId() != null
                ? new Notification.BubbleMetadata.Builder(bm.getShortcutId())
                : new Notification.BubbleMetadata.Builder(bm.getIntent(), bm.getIcon());
        if (bm.getDesiredHeight() > 0) b.setDesiredHeight(bm.getDesiredHeight());
        return b.setAutoExpandBubble(false).setSuppressNotification(suppress).build();
    }

    /*
     * Hội thoại đang có thông báo -> đã thấy nó "còn chưa đọc" trong danh sách chat sau khi báo chưa. Đọc ở máy khác
     * (điện thoại, Zalo PC): Zalo Web nhận đồng bộ, số chưa đọc về 0 -> xoá thông báo. Phải thấy "còn chưa đọc" trước,
     * vì danh sách cũ (chưa kịp tăng số chưa đọc) có thể tới ngay sau thông báo mới và làm xoá nhầm.
     */
    private static final java.util.Map<String, Boolean> shownConv = new java.util.concurrent.ConcurrentHashMap<>();
    /** Tên, ảnh đại diện, tin cuối của mỗi hội thoại (từ danh sách chat): dựng bong bóng khi vuốt app về màn hình chính. */
    private static final java.util.Map<String, String[]> convInfo = new java.util.concurrent.ConcurrentHashMap<>();
    /** Hội thoại là nhóm hay không (từ danh sách chat), để lọc bong bóng theo Cài đặt. */
    private static final java.util.Map<String, Boolean> groupConv = new java.util.concurrent.ConcurrentHashMap<>();
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
                groupConv.put(id, o.optBoolean("g"));
                convInfo.put(id, new String[]{o.optString("n"), o.optString("a"), o.optString("m")});
                Boolean seenUnread = shownConv.get(id);
                if (seenUnread == null) continue;
                if (unread) {
                    if (!seenUnread) shownConv.put(id, true);
                } else if (seenUnread) {
                    shownConv.remove(id);
                    int nid = idForTag(id);
                    cancelKeepBubble(c, nid);
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
                if (id != KeepAliveService.NOTIF_ID && id != KeepAliveService.CALL_NOTIF_ID && id != CALL_ID) nm(c).cancel(sbn.getTag(), id);
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
                                             Bitmap icon, boolean silent, int nid, String tag) {
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
        if (Build.VERSION.SDK_INT >= 30 && wantBubble(c, tag)) {
            try {
                addBubble(c, b, tag, title.isEmpty() ? c.getString(R.string.app_name) : title, body, icon, nid,
                        Prefs.bubbleAutoExpand(c), Prefs.bubbleHideNotif(c));
            } catch (RuntimeException e) {
                Log.w(WebViewHolder.TAG, "bubble", e); // không làm hỏng thông báo thường
            }
        }
        return b.build();
    }

    // ------------------------------------------------------------------ bong bóng chat (Android 11+)

    private static boolean wantBubble(Context c, String tag) {
        if (tag == null || tag.isEmpty() || !Prefs.bubbles(c)) return false;
        int scope = Prefs.bubbleScope(c);
        if (scope == 0) return true;
        Boolean g = groupConv.get(tag);
        if (g == null) return true; // chưa biết là nhóm hay không: vẫn hiện
        return scope == 1 ? !g : g;
    }

    /**
     * Bong bóng cần: kiểu tin nhắn (MessagingStyle + Person), một lối tắt hội thoại "sống lâu" gắn với thông báo, và
     * BubbleMetadata mở {@link BubbleActivity} (màn hình Zalo thu nhỏ, mở sẵn đúng hội thoại). Người dùng còn phải cho
     * phép bong bóng của app trong Cài đặt Android (xem SettingsActivity, trang Bong bóng chat).
     */
    @android.annotation.TargetApi(30)
    private static void addBubble(Context c, Notification.Builder b, String tag, String name, String body, Bitmap icon, int nid,
                                  boolean autoExpand, boolean suppress) {
        Icon personIcon = Icon.createWithAdaptiveBitmap(adaptive(icon, name));
        android.app.Person sender = new android.app.Person.Builder().setName(name).setIcon(personIcon)
                .setKey(tag).setImportant(true).build();
        android.app.Person me = new android.app.Person.Builder().setName("Bạn").build();
        b.setStyle(new Notification.MessagingStyle(me)
                .addMessage(body, System.currentTimeMillis(), sender));

        String shortcutId = "conv_" + tag;
        Intent open = BubbleActivity.intent(c, tag);
        android.content.pm.ShortcutInfo si = new android.content.pm.ShortcutInfo.Builder(c, shortcutId)
                .setShortLabel(name)
                .setLongLived(true)
                .setIcon(personIcon)
                .setPerson(sender)
                .setIntent(new Intent(open).setAction(Intent.ACTION_VIEW))
                .setCategories(Collections.singleton("app.zaloduo.client.category.CONVERSATION"))
                .build();
        c.getSystemService(android.content.pm.ShortcutManager.class).pushDynamicShortcut(si);
        b.setShortcutId(shortcutId);

        // Bong bóng bắt buộc PendingIntent MUTABLE (hệ thống thêm thông tin khi mở trong bong bóng).
        PendingIntent bubbleIntent = PendingIntent.getActivity(c, nid, open,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        b.setBubbleMetadata(new Notification.BubbleMetadata.Builder(bubbleIntent, personIcon)
                .setDesiredHeight(640)
                .setAutoExpandBubble(autoExpand)
                .setSuppressNotification(suppress)
                .build());
    }

    /**
     * Vuốt app về màn hình chính khi đang mở một hội thoại (MainActivity.onUserLeaveHint): hiện ngay bong bóng của hội
     * thoại đó để chat tiếp. Chỉ có bong bóng (không thêm thông báo, không kêu); bong bóng thu gọn, chạm để mở.
     */
    static void bubbleForConversation(Context c, String convId) {
        if (!Prefs.bubbleOnHome(c) || !wantBubble(c, convId)) return;
        postConversationBubble(c, convId, false);
    }

    /** Người dùng chọn "Đưa ra bong bóng chat" ở menu giữ lâu một hội thoại: luôn hiện (bỏ qua phạm vi trong Cài đặt). */
    static void bubbleOnRequest(Context c, String convId) {
        if (convId == null || convId.isEmpty()) return;
        if (Build.VERSION.SDK_INT < 30) {
            App.MAIN.post(() -> android.widget.Toast.makeText(c, "Bong bóng chat cần Android 11 trở lên",
                    android.widget.Toast.LENGTH_LONG).show());
            return;
        }
        postConversationBubble(c, convId, true); // người dùng chủ động chọn: mở luôn khung chat trong bong bóng
    }

    private static void postConversationBubble(Context c, String convId, boolean expand) {
        if (Build.VERSION.SDK_INT < 30) return;
        String[] info = convInfo.get(convId);
        String name = info != null && !info[0].isEmpty() ? info[0] : c.getString(R.string.app_name);
        String avatar = info != null ? info[1] : null;
        String last = info != null && !info[2].isEmpty() ? info[2] : name;
        int nid = idForTag(convId);
        App.IO.execute(() -> {
            Bitmap icon = loadIcon(avatar);
            Intent open = new Intent(c, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(MainActivity.EXTRA_CONV, convId);
            PendingIntent pi = PendingIntent.getActivity(c, nid, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b = new Notification.Builder(c, App.CH_BUBBLE)
                    .setSmallIcon(R.drawable.ic_stat_message)
                    .setColor(ZALO_BLUE)
                    .setContentTitle(name)
                    .setContentText(last)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            if (icon != null) b.setLargeIcon(icon);
            try {
                addBubble(c, b, convId, name, last, icon, nid, expand, true);
                post(c, nid, b.build());
                DiagLog.add("bubble for open conversation (app to home)");
            } catch (RuntimeException e) {
                Log.w(WebViewHolder.TAG, "home bubble", e);
            }
        });
    }

    /**
     * Icon "adaptive" của bong bóng: hệ thống cắt lấy vùng giữa (2/3) theo khuôn tròn. Ảnh phải phủ kín cả khung, không
     * chừa phần trong suốt — Huawei tô phần trong suốt thành nền đen vuông. Nền xanh Zalo, ảnh đại diện phủ kín; không có
     * ảnh thì chữ cái đầu của tên.
     */
    private static Bitmap adaptive(Bitmap src, String name) {
        int size = 216;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        cv.drawColor(ZALO_BLUE);
        if (src != null) {
            cv.drawBitmap(Bitmap.createScaledBitmap(src, size, size, true), 0, 0, null);
        } else {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFFFFFFF);
            p.setTextSize(64);
            p.setTextAlign(Paint.Align.CENTER);
            p.setFakeBoldText(true);
            String n = name == null || name.trim().isEmpty() ? "Z" : name.trim();
            String letter = n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase();
            Paint.FontMetrics fm = p.getFontMetrics();
            cv.drawText(letter, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, p);
        }
        return out;
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
