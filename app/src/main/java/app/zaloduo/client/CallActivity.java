package app.zaloduo.client;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Màn hình cuộc gọi thoại, theo kiểu Zalo trên điện thoại: nền xanh Zalo, ảnh đại diện tròn, tên, trạng thái (hoặc thời
 * gian khi đã nối), ba nút tròn Loa / Kết thúc / Mic.
 */
public class CallActivity extends Activity implements CallManager.Listener {
    private static final String EXTRA_UID = "uid", EXTRA_NAME = "name", EXTRA_AVATAR = "avatar", EXTRA_VIDEO = "video",
            EXTRA_GROUP = "group", EXTRA_MEMBERS = "members";
    private String pendingGroup, pendingMembers;
    private static final int REQ_MIC = 41;
    private static final int RED = 0xFFE5534B;
    /** Bảng màu: sáng = nền xanh Zalo; tối (Zalo để giao diện tối) = nền gần đen, nút xám. Chọn trong onCreate. */
    private int bg = 0xFF0068FF, btnBg = 0xFF0A55D6, ringBg = 0xFF2F7DFF, ringStroke = 0x55FFFFFF, onIcon = 0xFF0068FF;

    private TextView title, status, initials;
    private ImageView avatar, speakerIcon, micIcon;
    private View speakerBtn, micBtn;
    private String pendingUid, pendingName, pendingAvatar, loadedAvatar;
    private boolean pendingVideo;
    private View avatarBox, camBtn;
    private ImageView camIcon;
    private android.view.TextureView remoteView, localView;
    private android.view.Surface remoteSurface, localSurface;
    private CallVideo attached;
    private int btnSize = 72;
    private View normalRow, incomingRow;
    private static final int REQ_ANSWER = 42;

    /** Nút Trả lời: xin quyền micro (và camera nếu gọi video) rồi nhận cuộc gọi. */
    private void answerHere() {
        java.util.List<String> need = new java.util.ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (CallManager.get().isVideo() && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
        if (need.isEmpty()) CallManager.get().answer();
        else requestPermissions(need.toArray(new String[0]), REQ_ANSWER);
    }
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            CallManager m = CallManager.get();
            long at = m.connectedAt();
            if (m.state() == CallManager.State.CONNECTED && at > 0) {
                long s = (System.currentTimeMillis() - at) / 1000;
                String t = String.format(Locale.US, "%02d:%02d", s / 60, s % 60);
                if (m.isGroup()) t += " · " + (m.groupActiveCount() + 1) + " người";
                status.setText(t);
            }
            status.postDelayed(this, 500);
        }
    };

    /** Nút "Trả lời" trên thông báo cuộc gọi đến: nhận cuộc gọi rồi mở màn hình cuộc gọi. */
    static void answerFromNotification(Context c) {
        boolean mic = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        boolean cam = !CallManager.get().isVideo() || c.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if (mic && cam) CallManager.get().answer(); // thiếu quyền: mở màn hình, người dùng bấm Trả lời để cấp
        c.startActivity(new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Gọi thoại nhóm (gọi từ luồng chính). */
    static void dialGroup(Context c, String gid, String name, String avatarUrl, String members) {
        Intent i = new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!CallManager.get().busy()) {
            i.putExtra(EXTRA_UID, "g" + gid).putExtra(EXTRA_NAME, name).putExtra(EXTRA_AVATAR, avatarUrl)
                    .putExtra(EXTRA_GROUP, gid).putExtra(EXTRA_MEMBERS, members);
        }
        c.startActivity(i);
    }

    /** Mở màn hình cuộc gọi và gọi đi (gọi từ luồng chính). */
    static void dial(Context c, String uid, String name, String avatarUrl, boolean video) {
        Intent i = new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!CallManager.get().busy()) i.putExtra(EXTRA_UID, uid).putExtra(EXTRA_NAME, name).putExtra(EXTRA_AVATAR, avatarUrl).putExtra(EXTRA_VIDEO, video);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Theo giao diện của Zalo (màu thanh dưới shim báo về, mục 9): Zalo để tối thì màn hình gọi cũng tối.
        if (Color.luminance(Prefs.barBottom(this)) < 0.5f) {
            bg = 0xFF16181C;
            btnBg = 0xFF2C3036;
            ringBg = 0xFF2C3036;
            ringStroke = 0x33FFFFFF;
            onIcon = 0xFF16181C;
        }
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        Intent i = getIntent();
        boolean videoMode = CallManager.get().busy() ? CallManager.get().isVideo()
                : i.getStringExtra(EXTRA_UID) != null && i.getBooleanExtra(EXTRA_VIDEO, false);
        buildUi(videoMode);
        String uid = i.getStringExtra(EXTRA_UID);
        if (uid != null && b == null && !CallManager.get().busy()) {
            pendingUid = uid;
            pendingName = i.getStringExtra(EXTRA_NAME);
            pendingAvatar = i.getStringExtra(EXTRA_AVATAR);
            title.setText(pendingName == null || pendingName.isEmpty() ? uid : pendingName);
            status.setText("Đang nối máy đến người nhận");
            showAvatar(pendingAvatar, pendingName);
            pendingVideo = i.getBooleanExtra(EXTRA_VIDEO, false);
            pendingGroup = i.getStringExtra(EXTRA_GROUP);
            pendingMembers = i.getStringExtra(EXTRA_MEMBERS);
            if (pendingGroup != null) pendingVideo = false;
            java.util.List<String> need = new java.util.ArrayList<>();
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
            if (pendingVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.CAMERA);
            if (need.isEmpty()) startPending();
            else requestPermissions(need.toArray(new String[0]), REQ_MIC);
        }
    }

    private void startPending() {
        if (pendingUid == null) return;
        String uid = pendingUid;
        pendingUid = null;
        if (pendingGroup != null) CallManager.get().startGroup(this, pendingGroup, pendingName, pendingAvatar, pendingMembers);
        else CallManager.get().startOutgoing(this, uid, pendingName, pendingAvatar, pendingVideo);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_ANSWER) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) CallManager.get().answer();
            else Toast.makeText(this, "Cần quyền micro để nghe máy", Toast.LENGTH_LONG).show();
            return;
        }
        if (code != REQ_MIC) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Cần quyền micro để gọi", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (pendingVideo && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Không có quyền camera: gọi thoại", Toast.LENGTH_SHORT).show();
            pendingVideo = false;
        }
        startPending();
    }

    /** Màn hình cuộc gọi đang hiện: thông báo cuộc gọi để im lặng; ẩn đi thì thông báo bật popup. */
    static volatile boolean visible;

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        CallManager.get().setListener(this);
        status.post(tick);
        KeepAliveService.refreshNotification();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        CallManager.get().setListener(null);
        status.removeCallbacks(tick);
        KeepAliveService.refreshNotification();
    }

    @Override
    public void onBackPressed() {
        finish(); // cuộc gọi vẫn tiếp tục (thông báo "đang gọi" của dịch vụ nền), mở lại từ thông báo hoặc nút gọi
    }

    @Override
    public void onCallState(CallManager.State s, String d) {
        CallManager m = CallManager.get();
        if (pendingUid != null) return; // đang chờ quyền micro
        title.setText(m.partnerName());
        showAvatar(m.partnerAvatar(), m.partnerName());
        switch (s) {
            case DIALING:
                status.setText("Đang nối máy đến người nhận");
                break;
            case CALLING:
                status.setText("Đang gọi…");
                break;
            case RINGING:
                status.setText("Đang đổ chuông…");
                break;
            case INCOMING:
                status.setText(d.isEmpty() ? "Cuộc gọi đến" : d);
                break;
            case CONNECTED:
                attachVideo();
                break; // tick hiển thị thời gian
            case ENDED:
                status.setText(d.isEmpty() ? "Cuộc gọi kết thúc" : d);
                status.postDelayed(this::finish, 1500);
                break;
            case IDLE:
                finish();
                break;
        }
        boolean ringing = s == CallManager.State.INCOMING;
        incomingRow.setVisibility(ringing ? View.VISIBLE : View.GONE);
        normalRow.setVisibility(ringing ? View.GONE : View.VISIBLE);
        setToggle(speakerBtn, speakerIcon, m.speaker());
        setToggle(micBtn, micIcon, m.muted());
        micIcon.setImageResource(m.muted() ? R.drawable.zd_ic_muted : R.drawable.zd_ic_mic);
        CallVideo v = m.video();
        if (camBtn != null) {
            boolean off = v != null && !v.cameraOn();
            setToggle(camBtn, camIcon, off);
            camIcon.setImageResource(off ? R.drawable.zd_ic_video_off : R.drawable.zd_ic_video);
        }
    }

    // ------------------------------------------------------------------ video

    /** Nối ô hình bên kia / ô xem trước camera vào bộ hình của cuộc gọi (khi đã nối và ô đã sẵn sàng). */
    private void attachVideo() {
        CallManager m = CallManager.get();
        if (!m.isVideo() || remoteView == null) return;
        CallVideo v = m.video();
        boolean show = v != null && m.state() == CallManager.State.CONNECTED;
        remoteView.setVisibility(show ? View.VISIBLE : View.GONE);
        localView.setVisibility(show ? View.VISIBLE : View.GONE);
        avatarBox.setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) return;
        if (attached != v) {
            attached = v;
            if (remoteSurface != null) v.setRemoteSurface(remoteSurface);
            if (localSurface != null) v.setPreviewSurface(localSurface);
        }
    }

    private android.view.TextureView videoView(boolean remote) {
        android.view.TextureView tv = new android.view.TextureView(this);
        tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                android.view.Surface s = new android.view.Surface(st);
                if (remote) remoteSurface = s; else localSurface = s;
                CallVideo v = attached;
                if (v != null) { if (remote) v.setRemoteSurface(s); else v.setPreviewSurface(s); }
            }
            @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture st, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture st) {
                CallVideo v = attached;
                if (v != null) { if (remote) v.setRemoteSurface(null); else v.setPreviewSurface(null); }
                if (remote) remoteSurface = null; else localSurface = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture st) { }
        });
        return tv;
    }

    /** Nút bật: nền trắng, icon xanh; tắt: nền xanh đậm, icon trắng. */
    private void setToggle(View btn, ImageView icon, boolean on) {
        ((GradientDrawable) btn.getBackground()).setColor(on ? Color.WHITE : btnBg);
        icon.setImageTintList(ColorStateList.valueOf(on ? onIcon : Color.WHITE));
    }

    // ------------------------------------------------------------------ ảnh đại diện

    private void showAvatar(String url, String name) {
        String n = name == null ? "" : name.trim();
        StringBuilder ini = new StringBuilder();
        for (String w : n.split("\\s+")) if (!w.isEmpty() && ini.length() < 2) ini.append(Character.toUpperCase(w.charAt(0)));
        if (avatar.getDrawable() == null) initials.setText(ini.length() == 0 ? "?" : ini.toString());
        if (url == null || url.isEmpty() || url.equals(loadedAvatar) || !url.startsWith("http")) return;
        loadedAvatar = url;
        App.IO.execute(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                try (InputStream in = c.getInputStream()) { bmp = BitmapFactory.decodeStream(in); }
            } catch (Exception ignored) { }
            final Bitmap fb = bmp;
            if (fb != null) {
                CallManager.get().setAvatarBitmap(fb);
                runOnUiThread(() -> { avatar.setImageBitmap(fb); initials.setText(""); });
            }
        });
    }

    // ------------------------------------------------------------------ giao diện

    private void buildUi(boolean videoMode) {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(bg);
        if (videoMode) {
            // Hình bên kia toàn màn hình (dưới cùng), xem trước camera của mình ở góc trên phải.
            remoteView = videoView(true);
            remoteView.setVisibility(View.GONE);
            frame.addView(remoteView, new FrameLayout.LayoutParams(-1, -1));
            localView = videoView(false);
            localView.setVisibility(View.GONE);
            localView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
                }
            });
            localView.setClipToOutline(true);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(dp(108), dp(144), Gravity.BOTTOM | Gravity.END);
            llp.bottomMargin = dp(150); // trên hàng nút
            llp.rightMargin = dp(16);
            localView.setOnClickListener(v -> { CallVideo cv = CallManager.get().video(); if (cv != null) cv.switchCamera(); });
            frame.addView(localView, llp);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(36), dp(16), dp(40));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));

        // Thanh trên: nút quay lại, chữ "Zalo"
        FrameLayout bar = new FrameLayout(this);
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.zd_ic_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
        back.setBackground(circle(btnBg));
        back.setOnClickListener(v -> finish());
        back.setContentDescription("Quay lại");
        bar.addView(back, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
        TextView brand = text("Zalo", 24, true);
        bar.addView(brand, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));

        // Ảnh đại diện
        FrameLayout av = new FrameLayout(this);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(ringBg);
        ring.setStroke(dp(3), ringStroke);
        av.setBackground(ring);
        av.setPadding(dp(4), dp(4), dp(4), dp(4));
        initials = text("", 52, true);
        initials.setGravity(Gravity.CENTER);
        av.addView(initials, new FrameLayout.LayoutParams(-1, -1));
        avatar = new ImageView(this);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) { outline.setOval(0, 0, view.getWidth(), view.getHeight()); }
        });
        avatar.setClipToOutline(true);
        av.addView(avatar, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(176), dp(176));
        lp.topMargin = dp(76);
        root.addView(av, lp);
        avatarBox = av;

        title = text("", 28, true);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(20);
        root.addView(title, lp);

        status = text("", 17, false);
        status.setGravity(Gravity.CENTER);
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        root.addView(status, lp);

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // Nút: Loa / Kết thúc / Mic; gọi video thêm Camera và Đổi camera
        btnSize = videoMode ? 60 : 72;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        speakerIcon = new ImageView(this);
        speakerBtn = roundButton(row, speakerIcon, R.drawable.ic_speaker, btnBg, "Loa",
                v -> CallManager.get().setSpeaker(!CallManager.get().speaker()));
        if (videoMode) {
            camIcon = new ImageView(this);
            camBtn = roundButton(row, camIcon, R.drawable.zd_ic_video, btnBg, "Camera", v -> {
                CallVideo cv = CallManager.get().video();
                if (cv == null) return;
                CallManager.get().setCameraOn(!cv.cameraOn());
                localView.setAlpha(cv.cameraOn() ? 1f : 0f);
            });
        }
        ImageView endIcon = new ImageView(this);
        roundButton(row, endIcon, R.drawable.zd_ic_call_end, RED, "Kết thúc", v -> {
            if (CallManager.get().busy()) CallManager.get().hangup(null);
            else finish();
        });
        micIcon = new ImageView(this);
        micBtn = roundButton(row, micIcon, R.drawable.zd_ic_mic, btnBg, "Mic",
                v -> CallManager.get().setMuted(!CallManager.get().muted()));
        if (videoMode) {
            roundButton(row, new ImageView(this), R.drawable.zd_ic_cam_switch, btnBg, "Đổi cam", v -> {
                CallVideo cv = CallManager.get().video();
                if (cv != null) cv.switchCamera();
            });
        }
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        normalRow = row;

        // Cuộc gọi đến: Từ chối / Trả lời
        int saved = btnSize;
        btnSize = 72;
        LinearLayout in = new LinearLayout(this);
        in.setGravity(Gravity.CENTER);
        roundButton(in, new ImageView(this), R.drawable.zd_ic_call_end, RED, "Từ chối", v -> CallManager.get().decline());
        ImageView ansIcon = new ImageView(this);
        roundButton(in, ansIcon, videoMode ? R.drawable.zd_ic_video : R.drawable.zd_ic_phone, 0xFF30A46C, "Trả lời", v -> answerHere());
        btnSize = saved;
        in.setVisibility(View.GONE);
        root.addView(in, new LinearLayout.LayoutParams(-1, -2));
        incomingRow = in;

        setContentView(frame);
    }

    private View roundButton(LinearLayout row, ImageView icon, int iconRes, int color, String label, View.OnClickListener l) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout btn = new FrameLayout(this);
        btn.setBackground(circle(color));
        btn.setOnClickListener(l);
        btn.setContentDescription(label);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        btn.addView(icon, new FrameLayout.LayoutParams(dp(btnSize * 4 / 9), dp(btnSize * 4 / 9), Gravity.CENTER));
        col.addView(btn, new LinearLayout.LayoutParams(dp(btnSize), dp(btnSize)));
        TextView t = text(label, btnSize < 72 ? 13 : 16, false);
        t.setShadowLayer(4, 0, 1, 0x80000000); // đọc được trên hình video
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(10);
        col.addView(t, lp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
        row.addView(col, clp);
        return btn;
    }

    private GradientDrawable circle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
