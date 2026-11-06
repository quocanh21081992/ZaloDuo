package app.zaloduo.client;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;

/**
 * Thông báo kiểu cuộc gọi (Notification.CallStyle, Android 12+) cho cuộc gọi của app — là thông báo của dịch vụ nền
 * (KeepAliveService), nên luôn có khi đang gọi.
 * <ul>
 * <li>Đang gọi / đã nối: nút Kết thúc; đang ở màn hình cuộc gọi thì để kênh im lặng, ra khỏi app thì chuyển sang kênh
 * "Cuộc gọi đang diễn ra" (mức cao) để hiện popup trên đầu màn hình.</li>
 * <li>Cuộc gọi đến: nút Từ chối / Trả lời, toàn màn hình khi máy đang khoá (full-screen intent).</li>
 * </ul>
 */
@TargetApi(31)
final class CallNotify {
    private CallNotify() {}

    static Notification build(Context c) {
        CallManager m = CallManager.get();
        CallManager.State st = m.state();
        boolean incoming = st == CallManager.State.INCOMING;
        Person.Builder pb = new Person.Builder().setName(m.partnerName()).setImportant(true);
        Bitmap av = m.avatarBitmap();
        if (av != null) pb.setIcon(Icon.createWithAdaptiveBitmap(av));
        Person who = pb.build();

        PendingIntent open = PendingIntent.getActivity(c, 110,
                new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent hangup = PendingIntent.getService(c, 111,
                new Intent(c, KeepAliveService.class).setAction(KeepAliveService.ACTION_HANGUP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        String channel;
        if (incoming) channel = App.CH_CALL_INCOMING;
        else channel = CallActivity.visible ? App.CH_SERVICE : App.CH_CALL_ONGOING;

        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setColor(0xFF0068FF)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(!incoming)
                .setCategory(Notification.CATEGORY_CALL)
                .addPerson(who);
        if (incoming) {
            PendingIntent answer = PendingIntent.getService(c, 112,
                    new Intent(c, KeepAliveService.class).setAction(KeepAliveService.ACTION_ANSWER),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.setStyle(Notification.CallStyle.forIncomingCall(who, hangup, answer))
                    .setContentText("Cuộc gọi thoại Zalo đến")
                    .setFullScreenIntent(open, true);
        } else {
            b.setStyle(Notification.CallStyle.forOngoingCall(who, hangup));
            long at = m.connectedAt();
            if (st == CallManager.State.CONNECTED && at > 0) {
                b.setUsesChronometer(true).setWhen(at).setShowWhen(true).setContentText("Đang trong cuộc gọi");
            } else {
                b.setContentText(m.detail().isEmpty() ? "Đang gọi…" : m.detail());
            }
        }
        return b.build();
    }
}
