package app.zaloduo.client;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Dịch vụ chạy nền (foreground service) giữ cho tiến trình — và WebView Zalo bên trong — sống,
 * để websocket của Zalo Web không bị ngắt khi tắt màn hình. Trong lúc gọi, dịch vụ được nâng quyền
 * micro/camera để cuộc gọi không bị tắt tiếng khi chuyển sang app khác.
 */
public class KeepAliveService extends Service {
    static final int NOTIF_ID = 1;
    static final String ACTION_SPEAKER = "app.zaloduo.client.SPEAKER";
    static final String ACTION_HANGUP = "app.zaloduo.client.HANGUP";
    static final String ACTION_MIC = "app.zaloduo.client.MIC";
    static final String ACTION_ANSWER = "app.zaloduo.client.ANSWER";

    /** Cuộc gọi của app đổi trạng thái / màn hình cuộc gọi ẩn-hiện: dựng lại thông báo. Gọi trên luồng chính. */
    static void refreshNotification() {
        KeepAliveService s = instance;
        if (s != null) s.goForeground();
    }

    // Chỉ truy cập trên luồng chính.
    private static KeepAliveService instance;
    private static boolean mediaActive;
    private static boolean mediaVideo;
    private static boolean speakerOn;
    private static String conn = ""; // trạng thái kết nối máy chủ Zalo do shim.js báo: on / wait / off

    private PowerManager.WakeLock proximityLock;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, KeepAliveService.class));
        } catch (RuntimeException e) {
            Log.w(WebViewHolder.TAG, "Cannot start keep-alive service", e);
        }
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, KeepAliveService.class));
    }

    static boolean isSpeakerOn() {
        return speakerOn;
    }

    static boolean isMediaActive() {
        return mediaActive;
    }

    /** Màn hình bật/tắt, máy vào/ra Doze: ghi nhật ký chẩn đoán thông báo. */
    private final BroadcastReceiver diagReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                DiagLog.sys("screen on");
                WebViewHolder.wake("screen on");
                scheduleCheck(c, CHECK_OFF_MS); // dùng máy lại: giữ kết nối tiếp
            } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                WebViewHolder.wake("unlocked");
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                DiagLog.sys("screen off");
                // Khoá máy: thôi giữ kết nối (không hẹn giờ đánh thức nữa), tới khi bật màn hình lại — trừ khi người dùng
                // bật "Giữ kết nối khi khoá màn hình" (để nhận cuộc gọi đến).
                if (Prefs.keepOnLock(c)) {
                    scheduleCheck(c, CHECK_LOCKED_MS);
                    DiagLog.conn("screen locked: keep-alive continues (keep connection when locked)");
                } else if (!mediaActive) {
                    cancelCheck(c);
                    if (checkLock != null && checkLock.isHeld()) checkLock.release();
                    DiagLog.conn("screen locked: keep-alive paused");
                }
            } else if (PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(a)) {
                PowerManager pm = c.getSystemService(PowerManager.class);
                boolean idle = pm != null && pm.isDeviceIdleMode();
                DiagLog.sys(idle ? "doze on" : "doze off");
                if (!idle) WebViewHolder.wake("doze off");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DiagLog.sys("service start");
        IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        registerReceiver(diagReceiver, f);
        App.MAIN.postDelayed(tick, TICK_MS);
        WebViewHolder.get(this); // khởi động Zalo Web ngay cả khi chưa mở màn hình (ví dụ sau khi bật máy)
    }

    /*
     * Phát hiện app bị hệ thống "đóng băng" (dừng hẳn cả tiến trình dù có dịch vụ chạy nền — vd. quản lý pin của
     * Huawei / Xiaomi): nhịp 30 s của luồng chính đến trễ nhiều thì ghi vào nhật ký kết nối. Đồng hồ elapsedRealtime
     * vẫn chạy khi máy ngủ, nên chỉ tính lúc màn hình bật (khoá máy thì CPU ngủ là bình thường).
     */
    private static final long TICK_MS = 30_000;
    private long lastTick = android.os.SystemClock.elapsedRealtime();
    private boolean lastInteractive = true;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.elapsedRealtime(), gap = now - lastTick;
            PowerManager pm = getSystemService(PowerManager.class);
            boolean on = pm == null || pm.isInteractive();
            if (gap > 3 * TICK_MS && on && lastInteractive) {
                DiagLog.conn("app process was paused ~" + gap / 1000 + "s while the screen was on (system froze the app)");
            }
            lastTick = now;
            lastInteractive = on;
            App.MAIN.postDelayed(this, TICK_MS);
        }
    };

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        DiagLog.sys("app swiped away from recents");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        if (intent != null && ACTION_SPEAKER.equals(intent.getAction())) setSpeaker(this, !speakerOn);
        if (intent != null && ACTION_HANGUP.equals(intent.getAction())) CallManager.get().hangup(null);
        if (intent != null && ACTION_MIC.equals(intent.getAction())) CallManager.get().setMuted(!CallManager.get().muted());
        if (intent != null && ACTION_ANSWER.equals(intent.getAction())) CallActivity.answerFromNotification(this);
        if (intent != null && ACTION_CHECK.equals(intent.getAction())) check();
        else scheduleCheck(this, CHECK_ON_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        DiagLog.sys("service stop");
        App.MAIN.removeCallbacks(tick);
        try {
            unregisterReceiver(diagReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        instance = null;
        if (proximityLock != null && proximityLock.isHeld()) proximityLock.release();
        if (checkLock != null && checkLock.isHeld()) checkLock.release();
        android.app.AlarmManager am = getSystemService(android.app.AlarmManager.class);
        if (am != null) am.cancel(PendingIntent.getService(this, 103,
                new Intent(this, KeepAliveService.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ foreground

    /**
     * Thông báo cuộc gọi của app dùng mã riêng: cập nhật cùng một mã từ dạng cuộc gọi (CallStyle, có "người") về dạng
     * thường thì một số máy (Huawei) giữ tên người gọi làm tiêu đề thông báo chạy nền. Đổi mã khi vào/ra cuộc gọi.
     */
    static final int CALL_NOTIF_ID = 3;
    private int fgId = NOTIF_ID;

    private void goForeground() {
        Notification n = buildNotification();
        int id = CallManager.get().busy() && Build.VERSION.SDK_INT >= 31 ? CALL_NOTIF_ID : NOTIF_ID;
        if (Build.VERSION.SDK_INT >= 34) {
            int base = ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING;
            int media = mediaActive ? mediaTypes() : 0;
            boolean done = false;
            if (media != 0) {
                try {
                    startForeground(id, n, base | media);
                    done = true;
                } catch (RuntimeException e) {
                    Log.w(WebViewHolder.TAG, "Cannot add microphone/camera type", e);
                }
            }
            if (!done) {
                try {
                    startForeground(id, n, base);
                } catch (RuntimeException e) {
                    giveUp(e);
                    return;
                }
            }
        } else {
            try {
                startForeground(id, n);
            } catch (RuntimeException e) {
                giveUp(e);
                return;
            }
        }
        if (id != fgId) {
            // Thông báo của mã cũ không tự mất khi dịch vụ chuyển sang mã mới.
            getSystemService(android.app.NotificationManager.class).cancel(fgId);
            fgId = id;
        }
    }

    /**
     * Android không cho chạy nền lúc này (ví dụ bị hạn chế khi khởi động máy). Phải dừng dịch vụ ngay,
     * nếu không hệ thống sẽ giết app vì "startForegroundService() không gọi startForeground()".
     * Lần mở app tiếp theo sẽ bật lại.
     */
    private void giveUp(RuntimeException e) {
        Log.w(WebViewHolder.TAG, "startForeground failed, stopping keep-alive service", e);
        stopSelf();
    }

    private int mediaTypes() {
        int t = 0;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        if (mediaVideo && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            t |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
        }
        return t;
    }

    private Notification buildNotification() {
        if (CallManager.get().busy() && Build.VERSION.SDK_INT >= 31) return CallNotify.build(this);
        // Đang gọi bằng app (CallManager): bấm thông báo mở lại màn hình cuộc gọi.
        Class<?> target = CallManager.get().busy() ? CallActivity.class : MainActivity.class;
        PendingIntent open = PendingIntent.getActivity(this, 100,
                new Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent settings = PendingIntent.getActivity(this, 101,
                new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder b = new Notification.Builder(this, App.CH_SERVICE)
                .setSmallIcon(mediaActive ? R.drawable.ic_stat_call : R.drawable.ic_stat_message)
                .setColor(0xFF0068FF)
                .setContentTitle(getString(mediaActive ? R.string.svc_in_call : R.string.svc_running))
                .setContentText(mediaActive ? getString(R.string.svc_in_call_text) : connText())
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(mediaActive ? Notification.CATEGORY_CALL : Notification.CATEGORY_SERVICE);
        if (mediaActive) {
            PendingIntent speaker = PendingIntent.getService(this, 102,
                    new Intent(this, KeepAliveService.class).setAction(ACTION_SPEAKER),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_speaker),
                    getString(speakerOn ? R.string.action_speaker_off : R.string.action_speaker_on),
                    speaker).build());
        }
        b.addAction(new Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_tune),
                getString(R.string.action_settings), settings).build());
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return b.build();
    }

    // ------------------------------------------------------------------ trạng thái kết nối

    /** Dòng chữ thông báo theo trạng thái kết nối máy chủ Zalo (như nhãn dưới tên trên thanh trên của trang). */
    private String connText() {
        switch (conn) {
            case "on": return getString(R.string.svc_conn_on);
            case "wait": return getString(R.string.svc_conn_wait);
            case "off": return getString(R.string.svc_conn_off);
            default: return getString(R.string.svc_running_text);
        }
    }

    /** Shim báo trạng thái kết nối đổi: cập nhật thông báo chạy nền. Gọi trên luồng chính. */
    static void setConn(String state) {
        if (state.equals(conn)) return;
        conn = state;
        KeepAliveService s = instance;
        if (s == null) return;
        if (!mediaActive) s.goForeground();
        // Mất / đang nối lại: kiểm tra sớm (máy đang Doze thì Android dồn lại, ~9 phút một lần).
        scheduleCheck(s, "on".equals(state) ? CHECK_ON_MS : CHECK_OFF_MS);
    }

    /**
     * Có giữ kết nối không: đang dùng máy (màn hình bật), đang gọi, hoặc người dùng bật "Giữ kết nối khi khoá màn hình"
     * (để nhận cuộc gọi đến — tốn pin hơn vì máy bị đánh thức định kỳ).
     */
    private static boolean inUse(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return mediaActive || Prefs.keepOnLock(c) || pm == null || pm.isInteractive();
    }

    private static boolean locked(Context c) {
        PowerManager pm = c.getSystemService(PowerManager.class);
        return pm != null && !pm.isInteractive();
    }

    private static PendingIntent checkIntent(Context c) {
        return PendingIntent.getService(c, 103,
                new Intent(c, KeepAliveService.class).setAction(ACTION_CHECK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void cancelCheck(Context c) {
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        if (am != null) am.cancel(checkIntent(c));
    }

    // ------------------------------------------------------------------ tự kiểm tra kết nối khi chạy nền

    static final String ACTION_CHECK = "app.zaloduo.client.CHECK";
    /** Kiểm tra khi đang có kết nối: 2 phút (chỉ chạy lúc màn hình bật nên không tốn pin); mất kết nối: 30 s. */
    private static final long CHECK_ON_MS = 2 * 60 * 1000L, CHECK_OFF_MS = 30 * 1000L;
    private PowerManager.WakeLock checkLock;

    /**
     * Máy tắt màn hình lâu (Doze) thì CPU ngủ, bộ đếm giờ của trang dừng: Zalo không gửi được nhịp giữ kết nối, máy
     * chủ ngắt WebSocket, và Zalo không tự nối lại tới khi máy tỉnh — trạng thái treo "Đang kết nối". Hẹn giờ hệ thống
     * loại chạy cả khi Doze (setAndAllowWhileIdle) đánh thức app định kỳ: giữ CPU thức ~20 s cho trang chạy nhịp giữ
     * kết nối của Zalo, shim kiểm tra WebSocket và nối lại nếu đã đứt (mục 11), cập nhật trạng thái ở thông báo.
     * Chỉ khi đang dùng máy (màn hình bật) hoặc đang gọi: khoá máy thì huỷ hẹn giờ, thôi giữ kết nối (người dùng chọn).
     */
    static void scheduleCheck(Context c, long delayMs) {
        android.app.AlarmManager am = c.getSystemService(android.app.AlarmManager.class);
        if (am == null) return;
        if (!inUse(c)) { // khoá máy: không giữ kết nối
            am.cancel(checkIntent(c));
            return;
        }
        PendingIntent pi = checkIntent(c);
        try {
            am.setAndAllowWhileIdle(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + delayMs, pi);
        } catch (RuntimeException e) {
            Log.w(WebViewHolder.TAG, "schedule connection check", e);
        }
    }

    private void check() {
        if (!inUse(this)) { // hẹn giờ cũ tới lúc máy đã khoá: bỏ, không đánh thức trang
            DiagLog.conn("alarm check skipped: screen locked");
            return;
        }
        if (checkLock == null) {
            PowerManager pm = getSystemService(PowerManager.class);
            checkLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ZaloDuo:conncheck");
            checkLock.setReferenceCounted(false);
        }
        checkLock.acquire(20_000);
        DiagLog.add("connection check (" + (conn.isEmpty() ? "?" : conn) + ")");
        DiagLog.conn("alarm check, state=" + (conn.isEmpty() ? "?" : conn) + " | " + DiagLog.powerState(this));
        WebViewHolder.wake("alarm");
        WebViewHolder.evalMain("window.__zw&&window.__zw.connReport&&window.__zw.connReport()");
        // Khoá máy mà vẫn giữ kết nối: máy chủ ngắt socket im lặng sau ~8 phút (offline_timeout 480 s) và Zalo gửi nhịp
        // 3 phút/lần, nên đánh thức mỗi 2 phút cho trang kịp gửi nhịp. Android có thể dồn hẹn giờ khi Doze sâu.
        boolean lockedKeep = locked(this) && Prefs.keepOnLock(this) && !mediaActive;
        scheduleCheck(this, lockedKeep ? CHECK_LOCKED_MS : "on".equals(conn) ? CHECK_ON_MS : CHECK_OFF_MS);
    }

    private static final long CHECK_LOCKED_MS = 2 * 60 * 1000L;

    // ------------------------------------------------------------------ cuộc gọi

    /** Shim báo có/không còn dùng micro/camera (bắt đầu/kết thúc cuộc gọi). Gọi trên luồng chính. */
    static void setMediaActive(Context c, boolean active, boolean video) {
        boolean changed = mediaActive != active || mediaVideo != (active && video);
        mediaActive = active;
        mediaVideo = active && video;
        if (!changed) return;
        if (active) Notifier.cancelCallAlert(c);
        else if (speakerOn) setSpeaker(c, false);
        KeepAliveService s = instance;
        if (s != null) {
            s.goForeground();
            s.updateProximity();
        }
        MainActivity a = WebViewHolder.host();
        if (a != null) a.onMediaActiveChanged(active);
    }

    @SuppressWarnings("deprecation")
    static void setSpeaker(Context c, boolean on) {
        AudioManager am = c.getSystemService(AudioManager.class);
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                        if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                            am.setCommunicationDevice(d);
                            break;
                        }
                    }
                } else {
                    am.clearCommunicationDevice();
                }
            } else {
                am.setSpeakerphoneOn(on);
            }
        } catch (RuntimeException e) {
            Log.w(WebViewHolder.TAG, "setSpeaker", e);
        }
        boolean changed = speakerOn != on;
        speakerOn = on;
        KeepAliveService s = instance;
        if (s != null && changed) {
            s.goForeground();
            s.updateProximity();
        }
        if (changed) CallManager.get().refresh(); // nút Loa trên màn hình cuộc gọi
    }

    /** Gọi thoại (không video, không loa ngoài): áp tai vào thì tắt màn hình như app gọi thật. */
    private void updateProximity() {
        boolean want = mediaActive && !mediaVideo && !speakerOn;
        if (want) {
            if (proximityLock == null) {
                PowerManager pm = getSystemService(PowerManager.class);
                if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    proximityLock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "ZaloDuo:proximity");
                }
            }
            if (proximityLock != null && !proximityLock.isHeld()) proximityLock.acquire(2 * 60 * 60 * 1000L);
        } else if (proximityLock != null && proximityLock.isHeld()) {
            proximityLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY);
        }
    }
}
