package app.zaloduo.client;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Bong bóng chat tự vẽ, nổi trên các app khác (quyền "Hiển thị trên ứng dụng khác") — cho máy không có / không hiện
 * bong bóng của Android (Android dưới 11, Huawei / Honor). Một bong bóng cho hội thoại gần nhất: kéo để di chuyển (tự
 * dạt vào mép), chạm để mở ZaloDuo ngay hội thoại đó, kéo xuống dấu X ở đáy màn hình để đóng. Mở app thì ẩn đi.
 */
final class ChatHead {
    private ChatHead() {}

    private static WindowManager wm;
    private static FrameLayout head;
    private static WindowManager.LayoutParams lp;
    private static View closeTarget;
    private static String convId;

    static boolean canDraw(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Dùng bong bóng tự vẽ thay cho bong bóng của Android (theo Cài đặt; "Tự động": Android < 11 hoặc Huawei/Honor). */
    static boolean preferred(Context c) {
        int mode = Prefs.bubbleMode(c);
        if (mode == 1) return false;
        if (mode == 2) return true;
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        return Build.VERSION.SDK_INT < 30 || m.contains("huawei") || m.contains("honor");
    }

    /** Hiện (hoặc đổi) bong bóng cho một hội thoại. Gọi từ bất kỳ luồng nào. */
    static void show(Context ctx, String conv, String name, Bitmap avatar) {
        show(ctx, conv, name, avatar, false);
    }

    /** leaving: người dùng vừa rời app (onUserLeaveHint) — màn chính còn "đang hiện" thêm một nhịp, vẫn hiện bong bóng. */
    static void show(Context ctx, String conv, String name, Bitmap avatar, boolean leaving) {
        final Context c = ctx.getApplicationContext();
        if (!canDraw(c) || (!leaving && MainActivity.visible)) return;
        App.MAIN.post(() -> {
            try {
                showOnMain(c, conv, name, avatar, leaving);
            } catch (RuntimeException e) {
                DiagLog.add("chat head failed: " + e);
            }
        });
    }

    static void hide() {
        App.MAIN.post(() -> {
            removeClose();
            if (head != null && wm != null) {
                try { wm.removeViewImmediate(head); } catch (RuntimeException ignored) { }
            }
            head = null;
        });
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    private static int overlayType() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private static void showOnMain(Context c, String conv, String name, Bitmap avatar, boolean leaving) {
        if (!leaving && MainActivity.visible) return;
        convId = conv;
        if (wm == null) wm = c.getSystemService(WindowManager.class);
        int size = dp(c, 60);
        if (head == null) {
            head = new FrameLayout(c);
            lp = new WindowManager.LayoutParams(size, size, overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = 0;
            lp.y = dp(c, 160);
            head.setOnTouchListener(new Drag(c));
            wm.addView(head, lp);
        }
        head.removeAllViews();
        View face;
        if (avatar != null) {
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(avatar);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            face = iv;
        } else {
            TextView tv = new TextView(c);
            String n = name == null || name.isEmpty() ? "Z" : name.trim();
            tv.setText(n.substring(0, 1).toUpperCase());
            tv.setTextColor(Color.WHITE);
            tv.setTextSize(24);
            tv.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xFF0068FF);
            tv.setBackground(bg);
            face = tv;
        }
        face.setElevation(dp(c, 4));
        head.addView(face, new FrameLayout.LayoutParams(size - dp(c, 4), size - dp(c, 4), Gravity.CENTER));
        head.setContentDescription(name);
    }

    private static void open(Context c) {
        String conv = convId;
        hide();
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (conv != null) i.putExtra(MainActivity.EXTRA_CONV, conv);
        c.startActivity(i);
    }

    // ------------------------------------------------------------------ kéo / thả

    private static void showClose(Context c) {
        if (closeTarget != null) return;
        TextView x = new TextView(c);
        x.setText("✕");
        x.setTextColor(Color.WHITE);
        x.setTextSize(22);
        x.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC333333);
        x.setBackground(bg);
        int s = dp(c, 56);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(s, s, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = dp(c, 48);
        try {
            wm.addView(x, p);
            closeTarget = x;
        } catch (RuntimeException ignored) { }
    }

    private static void removeClose() {
        if (closeTarget != null && wm != null) {
            try { wm.removeViewImmediate(closeTarget); } catch (RuntimeException ignored) { }
        }
        closeTarget = null;
    }

    private static final class Drag implements View.OnTouchListener {
        private final Context c;
        private float downX, downY;
        private int startX, startY;
        private boolean moved;

        Drag(Context c) { this.c = c; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            if (lp == null || head == null) return false;
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!moved && Math.hypot(dx, dy) > dp(c, 8)) {
                        moved = true;
                        showClose(c);
                    }
                    if (moved) {
                        lp.x = startX + Math.round(dx);
                        lp.y = startY + Math.round(dy);
                        try { wm.updateViewLayout(head, lp); } catch (RuntimeException ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    removeClose();
                    if (!moved) {
                        if (e.getActionMasked() == MotionEvent.ACTION_UP) open(c);
                        return true;
                    }
                    // Thả gần dấu X ở giữa đáy màn hình: đóng bong bóng.
                    float cx = e.getRawX(), cy = e.getRawY();
                    if (Math.abs(cx - dm.widthPixels / 2f) < dp(c, 70) && cy > dm.heightPixels - dp(c, 170)) {
                        hide();
                        return true;
                    }
                    // Dạt vào mép gần nhất.
                    lp.x = cx < dm.widthPixels / 2f ? 0 : dm.widthPixels - lp.width;
                    lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - lp.height - dp(c, 48)));
                    try { wm.updateViewLayout(head, lp); } catch (RuntimeException ignored) { }
                    return true;
                }
            }
            return false;
        }
    }
}
