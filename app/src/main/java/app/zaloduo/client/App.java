package app.zaloduo.client;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    static final String CH_MESSAGES = "messages";
    static final String CH_MESSAGES_SILENT = "messages_silent";
    static final String CH_CALLS = "calls";
    static final String CH_CALLS_QUIET = "calls_quiet";
    static final String CH_SERVICE = "service";
    static final String CH_CALL_ONGOING = "call_ongoing";
    static final String CH_CALL_INCOMING = "call_incoming";

    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService IO = Executors.newFixedThreadPool(3);

    private static final long[] RING_VIBRATION = {0, 1000, 800, 1000, 800, 1000};

    /** Context của ứng dụng (cho cuộc gọi đến khi chưa có màn hình nào). */
    static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;
        CrashLog.install(this);
        DiagLog.init(this);
        CallEvents.init(this);
        DiagLog.sys("process start");
        // Bản debug: cho Chrome DevTools soi trang Zalo Web (chrome://inspect). Bản release không bật.
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true);
        }
        createChannels();
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);

        NotificationChannel messages = new NotificationChannel(
                CH_MESSAGES, getString(R.string.ch_messages), NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription(getString(R.string.ch_messages_desc));
        messages.enableVibration(true);
        messages.setShowBadge(true);

        NotificationChannel silent = new NotificationChannel(
                CH_MESSAGES_SILENT, getString(R.string.ch_messages_silent), NotificationManager.IMPORTANCE_LOW);
        silent.setShowBadge(true);

        AudioAttributes ringAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        NotificationChannel calls = new NotificationChannel(
                CH_CALLS, getString(R.string.ch_calls), NotificationManager.IMPORTANCE_HIGH);
        calls.setDescription(getString(R.string.ch_calls_desc));
        calls.setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), ringAttrs);
        calls.enableVibration(true);
        calls.setVibrationPattern(RING_VIBRATION);
        calls.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        calls.setBypassDnd(false);

        NotificationChannel callsQuiet = new NotificationChannel(
                CH_CALLS_QUIET, getString(R.string.ch_calls_quiet), NotificationManager.IMPORTANCE_HIGH);
        callsQuiet.setSound(null, null);
        callsQuiet.enableVibration(true);
        callsQuiet.setVibrationPattern(RING_VIBRATION);
        callsQuiet.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        NotificationChannel service = new NotificationChannel(
                CH_SERVICE, getString(R.string.ch_service), NotificationManager.IMPORTANCE_MIN);
        service.setDescription(getString(R.string.ch_service_desc));
        service.setShowBadge(false);

        // Cuộc gọi bằng app: popup khi đang gọi mà ra khỏi app (không chuông/rung); cuộc gọi đến (app tự đổ chuông
        // lặp lại bằng nhạc chuông của máy, kênh chỉ rung).
        NotificationChannel callOngoing = new NotificationChannel(
                CH_CALL_ONGOING, "Cuộc gọi đang diễn ra", NotificationManager.IMPORTANCE_HIGH);
        callOngoing.setSound(null, null);
        callOngoing.enableVibration(false);
        callOngoing.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationChannel callIncoming = new NotificationChannel(
                CH_CALL_INCOMING, "Cuộc gọi đến qua ZaloDuo", NotificationManager.IMPORTANCE_HIGH);
        callIncoming.setSound(null, null);
        callIncoming.enableVibration(true);
        callIncoming.setVibrationPattern(RING_VIBRATION);
        callIncoming.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

        nm.createNotificationChannels(Arrays.asList(messages, silent, calls, callsQuiet, service, callOngoing, callIncoming));
    }
}
