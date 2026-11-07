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
            EXTRA_GROUP = "group", EXTRA_MEMBERS = "members", EXTRA_TOTAL = "total";
    private String pendingGroup, pendingMembers;
    private static final int REQ_MIC = 41;
    private static final int RED = 0xFFE5534B;
    /** Bảng màu: sáng = nền xanh Zalo; tối (Zalo để giao diện tối) = nền gần đen, nút xám. Chọn trong onCreate. */
    private int bg = 0xFF0068FF, btnBg = 0xFF0A55D6, ringBg = 0xFF2F7DFF, ringStroke = 0x55FFFFFF, onIcon = 0xFF0068FF;

    private TextView title, status, initials, stats, brand;
    private ImageView switchBtn;

    private void setCrop(boolean crop) {
        cropVideo = crop;
        applyVideoTransform();
    }
    private boolean cropVideo;
    private int vidW, vidH;
    private ImageView avatar, speakerIcon, micIcon;
    private View speakerBtn, micBtn, recBtn, videoReqBtn;
    private boolean builtVideo;
    private android.app.AlertDialog inviteDialog;
    private Runnable afterCamera;
    private static final int REQ_CAMERA = 43;

    /** Chạy {@code r} khi đã có quyền camera (xin nếu chưa). */
    private void withCamera(Runnable r) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { r.run(); return; }
        afterCamera = r;
        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    /**
     * Gọi thoại -> gọi video: đã chuyển sang video thì dựng lại màn hình kiểu video; bên kia mời thì hỏi Đồng ý / Từ chối;
     * nút Video chỉ sáng khi đã nối máy.
     */
    private void updateVideoUpgrade() {
        CallManager m = CallManager.get();
        if (!builtVideo && m.busy() && m.isVideo()) { recreate(); return; }
        if (videoReqBtn != null) videoReqBtn.setAlpha(m.state() == CallManager.State.CONNECTED && !m.isGroup() ? 1f : 0.4f);
        if (m.videoInvite() && inviteDialog == null && !isFinishing()) {
            inviteDialog = new android.app.AlertDialog.Builder(this)
                    .setTitle("Chuyển sang gọi video?")
                    .setCancelable(false)
                    .setMessage(m.partnerName() + " đã bật camera và mời bạn chuyển sang gọi video.")
                    .setNegativeButton("Từ chối", (d, w) -> CallManager.get().declineVideo())
                    .setPositiveButton("Đồng ý", (d, w) -> withCamera(() -> CallManager.get().acceptVideo()))
                    .setOnDismissListener(d -> inviteDialog = null)
                    .show();
        } else if (!m.videoInvite() && inviteDialog != null) {
            inviteDialog.dismiss();
        }
    }
    private ImageView recIcon;
    private TextView recLabel;
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
                String st = Prefs.callStats(CallActivity.this) ? m.statsLine() : "";
                stats.setText(st);
                stats.setVisibility(st.isEmpty() || inPip ? View.GONE : View.VISIBLE);
            } else {
                stats.setVisibility(View.GONE);
            }
            brand.setVisibility(stats.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); // thông số thay chữ "Zalo"
            updateRecButton(); // thời gian đang ghi
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
    static void dialGroup(Context c, String gid, String name, String avatarUrl, String members, int total) {
        Intent i = new Intent(c, CallActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!CallManager.get().busy()) {
            i.putExtra(EXTRA_UID, "g" + gid).putExtra(EXTRA_NAME, name).putExtra(EXTRA_AVATAR, avatarUrl)
                    .putExtra(EXTRA_GROUP, gid).putExtra(EXTRA_MEMBERS, members).putExtra(EXTRA_TOTAL, total);
        }
        c.startActivity(i);
    }

    /** Gọi nhóm làm máy mọi thành viên đổ chuông: hỏi lại trước (nhóm đông người lỡ tay là phiền cả nhóm). */
    private void confirmGroupThen(Runnable go) {
        int total = getIntent().getIntExtra(EXTRA_TOTAL, 0);
        String who = total > 1 ? (total - 1) + " thành viên khác" : "các thành viên";
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Gọi nhóm " + (pendingName == null ? "" : pendingName) + "?")
                .setMessage("Máy của " + who + " trong nhóm sẽ đổ chuông.")
                .setPositiveButton("Gọi", (d, w) -> go.run())
                .setNegativeButton("Huỷ", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
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
        if (b == null && ACTION_ANSWER.equals(i.getAction())) answerHere();
    }

    /** Nút Trả lời trên thông báo cuộc gọi đến: mở màn hình này và nghe máy. */
    static final String ACTION_ANSWER = "app.zaloduo.client.CALL_ANSWER";

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        if (ACTION_ANSWER.equals(i.getAction())) answerHere();
    }

    private void startPending() {
        if (pendingUid == null) return;
        String uid = pendingUid;
        if (pendingGroup != null) {
            confirmGroupThen(() -> {
                pendingUid = null;
                CallManager.get().startGroup(this, pendingGroup, pendingName, pendingAvatar, pendingMembers);
            });
            return;
        }
        pendingUid = null;
        CallManager.get().startOutgoing(this, uid, pendingName, pendingAvatar, pendingVideo);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_CAMERA) {
            Runnable r = afterCamera;
            afterCamera = null;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { if (r != null) r.run(); }
            else Toast.makeText(this, "Cần quyền camera để gọi video", Toast.LENGTH_LONG).show();
            return;
        }
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
        leaveScreen();
    }

    /**
     * Rời màn hình cuộc gọi (nút quay lại): gọi video đang nối thì thu vào cửa sổ nổi (PiP) — vẫn thấy hình người kia khi
     * chat trong app hay ra ngoài; còn lại thì đóng màn hình, cuộc gọi vẫn chạy (thông báo "đang gọi" để mở lại).
     */
    private void leaveScreen() {
        if (pipAllowed() && enterPip()) return;
        finish();
    }

    // ------------------------------------------------------------------ cửa sổ nổi (PiP)

    private boolean inPip, pipMuted;

    private boolean pipAllowed() {
        CallManager m = CallManager.get();
        return m.isVideo() && m.state() == CallManager.State.CONNECTED
                && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** Tham số PiP: tỉ lệ theo hình người kia, hai nút Mic + Kết thúc, tự vào PiP khi về màn hình chính (12+). */
    private android.app.PictureInPictureParams pipParams() {
        android.app.PictureInPictureParams.Builder b = new android.app.PictureInPictureParams.Builder();
        int w = vidW > 0 ? vidW : 3, h = vidH > 0 ? vidH : 4;
        float r = (float) w / h;
        if (r < 0.42f) { w = 42; h = 100; } else if (r > 2.39f) { w = 239; h = 100; } // giới hạn tỉ lệ của Android
        b.setAspectRatio(new android.util.Rational(w, h));
        java.util.List<android.app.RemoteAction> acts = new java.util.ArrayList<>();
        // Nút phóng to ở giữa cửa sổ nổi là của hệ thống: bên dưới chỉ cần Mic + Kết thúc.
        boolean muted = pipMuted = CallManager.get().muted();
        android.app.PendingIntent mic = android.app.PendingIntent.getService(this, 120,
                new Intent(this, KeepAliveService.class).setAction(KeepAliveService.ACTION_MIC),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        acts.add(new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this,
                muted ? R.drawable.zd_ic_mic_off : R.drawable.zd_ic_mic),
                muted ? "Bật mic" : "Tắt mic", muted ? "Bật micro" : "Tắt micro", mic));
        android.app.PendingIntent end = android.app.PendingIntent.getService(this, 121,
                new Intent(this, KeepAliveService.class).setAction(KeepAliveService.ACTION_HANGUP),
                android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
        acts.add(new android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this, R.drawable.zd_ic_call_end),
                "Kết thúc", "Kết thúc cuộc gọi", end));
        b.setActions(acts);
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(pipAllowed());
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    private boolean enterPip() {
        try {
            return enterPictureInPictureMode(pipParams());
        } catch (Exception e) {
            CallEvents.log("enter pip failed " + e);
            return false;
        }
    }

    /** Cập nhật tham số PiP (tỉ lệ hình, tự vào PiP) khi trạng thái / kích thước hình đổi. */
    private void refreshPip() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try { setPictureInPictureParams(pipParams()); } catch (Exception ignored) { }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 11 trở xuống không tự vào PiP: bấm Home khi đang gọi video thì vào tay.
        if (android.os.Build.VERSION.SDK_INT < 31 && pipAllowed()) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean pip, android.content.res.Configuration cfg) {
        super.onPictureInPictureModeChanged(pip, cfg);
        inPip = pip;
        // Trong cửa sổ nổi chỉ hiện hình người kia (lấp đầy khung), ẩn mọi thứ khác.
        int other = pip ? View.GONE : View.VISIBLE;
        if (uiRoot != null) uiRoot.setVisibility(other);
        if (localView != null) localView.setVisibility(other);
        if (switchBtn != null) switchBtn.setVisibility(other);
        if (stats != null && pip) stats.setVisibility(View.GONE);
        boolean wasCrop = cropVideo;
        if (pip) setCrop(true); else setCrop(cropBeforePip);
        if (pip) cropBeforePip = wasCrop;
        if (!pip && CallManager.get().state() != CallManager.State.CONNECTED) finish();
        else if (!pip) setControls(true); // về màn hình đầy đủ: hiện nút, 5 giây sau tự ẩn
    }

    private boolean cropBeforePip;
    private View uiRoot;
    private boolean localPlaced, localMirror;
    private int insetTop, insetBottom;

    /** Đặt ô hình của mình ở (x, y), giữ trọn trong màn hình (không lấn thanh trạng thái / điều hướng). */
    private void placeLocal(float x, float y) {
        View p = (View) localView.getParent();
        if (p == null || p.getWidth() == 0) return;
        float w = localView.getWidth() > 0 ? localView.getWidth() : dp(108);
        float h = localView.getHeight() > 0 ? localView.getHeight() : dp(144);
        float m = dp(8);
        localView.setX(Math.max(m, Math.min(x, p.getWidth() - w - m)));
        localView.setY(Math.max(insetTop + m, Math.min(y, p.getHeight() - insetBottom - h - m)));
    }

    // ------------------------------------------------------------------ tự ẩn nút khi gọi video

    private static final long AUTO_HIDE_MS = 5000;
    private boolean controlsShown = true, autoHideArmed;
    private final Runnable autoHide = () -> setControls(false);

    /** Đang gọi video đã nối (màn hình đầy đủ): nút và thông tin được phép tự ẩn, chỉ để lại hình. */
    private boolean videoLive() {
        CallManager m = CallManager.get();
        return remoteView != null && !inPip && m.isVideo() && m.state() == CallManager.State.CONNECTED && m.video() != null;
    }

    /** Hiện / ẩn các nút và thông tin (mờ dần). Hiện lên thì sau 5 giây không thao tác lại tự ẩn. */
    private void setControls(boolean show) {
        if (uiRoot == null) return;
        uiRoot.removeCallbacks(autoHide);
        if (!show && !videoLive()) show = true; // chưa nối / đã kết thúc / cuộc gọi thoại: luôn hiện
        controlsShown = show;
        uiRoot.animate().cancel();
        if (show) {
            if (!inPip) uiRoot.setVisibility(View.VISIBLE);
            uiRoot.animate().alpha(1f).setDuration(250).start();
            if (videoLive()) uiRoot.postDelayed(autoHide, AUTO_HIDE_MS);
        } else {
            uiRoot.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                if (!controlsShown) uiRoot.setVisibility(View.GONE);
            }).start();
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        // Đang hiện nút: mỗi lần chạm (bấm nút…) tính lại 5 giây trước khi tự ẩn.
        if (ev.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && controlsShown && uiRoot != null
                && videoLive()) {
            uiRoot.removeCallbacks(autoHide);
            uiRoot.postDelayed(autoHide, AUTO_HIDE_MS);
        }
        return super.dispatchTouchEvent(ev);
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
                refreshPip(); // bật tự vào cửa sổ nổi khi về màn hình chính
                if (!autoHideArmed && videoLive()) { autoHideArmed = true; setControls(true); } // 5 giây sau thì ẩn nút
                if (inPip) { // attachVideo vừa hiện lại các nút: trong cửa sổ nổi chỉ giữ hình người kia
                    uiRoot.setVisibility(View.GONE);
                    if (localView != null) localView.setVisibility(View.GONE);
                    if (switchBtn != null) switchBtn.setVisibility(View.GONE);
                }
                break; // tick hiển thị thời gian
            case ENDED:
                status.setText(d.isEmpty() ? "Cuộc gọi kết thúc" : d);
                setControls(true);
                if (inPip) finish(); // cửa sổ nổi: đóng ngay
                else status.postDelayed(this::finish, 1500);
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
        micIcon.setImageResource(m.muted() ? R.drawable.zd_ic_mic_off : R.drawable.zd_ic_mic);
        if (inPip && pipMuted != m.muted()) { pipMuted = m.muted(); refreshPip(); } // đổi biểu tượng mic trên cửa sổ nổi
        updateRecButton();
        updateVideoUpgrade();
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
        // Đang gọi video: ẩn tên lớn (chỉ giữ đồng hồ ở giữa) để hộp thông số góc trái không đè chữ; nút đổi camera
        // chỉ có khi camera đang chạy.
        title.setVisibility(show ? View.GONE : View.VISIBLE);
        switchBtn.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        if (attached != v) {
            attached = v;
            v.setSizeListener((w, h) -> { vidW = w; vidH = h; applyVideoTransform(); refreshPip(); });
            if (remoteSurface != null) v.setRemoteSurface(remoteSurface);
            if (localSurface != null) v.setPreviewSurface(localSurface);
        }
    }

    /**
     * TextureView mặc định kéo giãn hình cho vừa ô (méo). Đặt ma trận: giữ đúng tỉ lệ video — vừa khít (có viền) hoặc,
     * khi bấm toàn màn hình, phủ kín ô và cắt phần thừa ở giữa.
     */
    private void applyVideoTransform() {
        if (remoteView == null || vidW <= 0 || vidH <= 0) return;
        float vw = remoteView.getWidth(), vh = remoteView.getHeight();
        if (vw <= 0 || vh <= 0) return;
        float scale = cropVideo ? Math.max(vw / vidW, vh / vidH) : Math.min(vw / vidW, vh / vidH);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(vidW * scale / vw, vidH * scale / vh, vw / 2f, vh / 2f);
        remoteView.setTransform(m);
    }

    private android.view.TextureView videoView(boolean remote) {
        android.view.TextureView tv = new android.view.TextureView(this);
        tv.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture st, int w, int h) {
                // Ô xem trước: camera đổ đúng 4:3 (640x480, Android tự xoay thành 3:4 khi cầm dọc) khớp với ô 108x144;
                // để mặc định thì camera chọn cỡ khác (thường 16:9) và hình bị kéo giãn.
                if (!remote) st.setDefaultBufferSize(CallVideo.W, CallVideo.H);
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

    /** Nút ghi âm: đang ghi = nền trắng, ô vuông đỏ (bấm để dừng), nhãn "Dừng mm:ss" (thời gian đã ghi); chưa nối máy thì mờ. */
    private void updateRecButton() {
        if (recBtn == null) return;
        CallManager m = CallManager.get();
        boolean on = m.recording();
        recBtn.setAlpha(m.state() == CallManager.State.CONNECTED ? 1f : 0.4f);
        ((GradientDrawable) recBtn.getBackground()).setColor(on ? Color.WHITE : btnBg);
        recIcon.setImageResource(on ? R.drawable.zd_ic_record_stop : R.drawable.zd_ic_record);
        recIcon.setImageTintList(ColorStateList.valueOf(on ? RED : Color.WHITE));
        if (on) {
            long s = Math.max(0, (System.currentTimeMillis() - m.recordingSince()) / 1000);
            recLabel.setText(String.format(Locale.US, "Dừng %d:%02d", s / 60, s % 60));
        } else {
            recLabel.setText("Ghi âm");
        }
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
            Bitmap bmp = loadBitmap(hdAvatar(url));
            if (bmp == null) bmp = loadBitmap(url);
            final Bitmap fb = bmp;
            if (fb != null) {
                CallManager.get().setAvatarBitmap(fb);
                runOnUiThread(() -> { avatar.setImageBitmap(fb); initials.setText(""); });
            }
        });
    }

    /**
     * Ảnh đại diện Zalo cỡ lớn nhất có trên máy chủ ảnh: cỡ do tên máy (s120-ava-talk = 120px), lớn nhất là s240.
     * Mọi dạng (…/2/e/1/1/57/120/<mã>.jpg, s160-26-ava-talk…/57/<mã>.jpg?key=…) đổi về s240-ava-talk…/57/240/<mã>.jpg
     * (thử 2026-11-06: cỡ 480 trở lên không có).
     */
    private static final java.util.regex.Pattern AVA =
            java.util.regex.Pattern.compile("^https?://[a-z0-9-]*ava-talk\\.zadn\\.vn/(?:.*?/)?(\\d+)/(?:\\d+/)?([0-9a-f]{32})\\.jpg");

    static String hdAvatar(String url) {
        if (url == null) return null;
        java.util.regex.Matcher m = AVA.matcher(url);
        return m.find() ? "https://s240-ava-talk.zadn.vn/" + m.group(1) + "/240/" + m.group(2) + ".jpg" : url;
    }

    static Bitmap loadBitmap(String url) {
        if (url == null || !url.startsWith("http")) return null;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return BitmapFactory.decodeStream(in); }
        } catch (Exception e) {
            return null;
        }
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
            // Ô hình của mình: kéo đi đâu cũng được (giữ trong màn hình), mặc định góc trên bên phải dưới thanh trên;
            // chạm (không kéo) thì lật hình (soi gương); đổi camera bằng nút góc trên phải.
            frame.addView(localView, new FrameLayout.LayoutParams(dp(108), dp(144), Gravity.TOP | Gravity.START));
            int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            localView.setOnTouchListener(new View.OnTouchListener() {
                float downX, downY, startX, startY;
                boolean moved;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    switch (e.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = e.getRawX(); downY = e.getRawY(); startX = v.getX(); startY = v.getY(); moved = false;
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE: {
                            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                            if (!moved && Math.hypot(dx, dy) > slop) moved = true;
                            if (moved) placeLocal(startX + dx, startY + dy);
                            return true;
                        }
                        case android.view.MotionEvent.ACTION_UP:
                            if (!moved) { // chạm: lật hình của mình (soi gương) — chỉ đổi cách hiện trên máy mình
                                localMirror = !localMirror;
                                v.setScaleX(localMirror ? -1f : 1f);
                            }
                            return true;
                    }
                    return true;
                }
            });
            frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (!localPlaced && r - l > 0) {
                    localPlaced = true;
                    placeLocal(r - l - dp(16) - dp(108), insetTop + dp(116)); // góc trên phải, dưới thanh trên (hộp thông số tới 5 dòng)
                } else {
                    placeLocal(localView.getX(), localView.getY()); // khung đổi cỡ (thoát PiP…): giữ trong màn hình
                }
            });
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(28));
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        uiRoot = root;
        // Android 15+ (targetSdk 35) vẽ tràn dưới thanh trạng thái / điều hướng: chừa đúng chiều cao của chúng.
        frame.setOnApplyWindowInsetsListener((v, ins) -> {
            int top = ins.getSystemWindowInsetTop(), bottom = ins.getSystemWindowInsetBottom();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = ins.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
            }
            root.setPadding(dp(16), top + dp(12), dp(16), bottom + dp(28));
            insetTop = top;
            insetBottom = bottom;
            return ins;
        });
        if (localView != null) localView.bringToFront(); // nằm trên các nút để luôn kéo được

        // Thanh trên: nút quay lại, giữa là chữ "Zalo" — khi đã nối thì thay bằng thông số cuộc gọi (ping, tốc độ tiếng /
        // hình; nền tối trong 50%, tắt được trong Cài đặt → Cuộc gọi), bên phải là nút đổi camera (gọi video).
        FrameLayout bar = new FrameLayout(this);
        bar.setMinimumHeight(dp(48));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.zd_ic_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
        back.setBackground(circle(btnBg));
        back.setOnClickListener(v -> leaveScreen());
        back.setContentDescription("Quay lại");
        bar.addView(back, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
        brand = text("Zalo", 24, true);
        bar.addView(brand, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        stats = text("", 11, false);
        stats.setGravity(Gravity.START);
        stats.setLineSpacing(0, 1.1f);
        GradientDrawable sbg = new GradientDrawable();
        sbg.setCornerRadius(dp(8));
        sbg.setColor(0x80000000);
        stats.setBackground(sbg);
        stats.setPadding(dp(8), dp(4), dp(8), dp(4));
        stats.setVisibility(View.GONE);
        bar.addView(stats, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        if (videoMode) {
            switchBtn = new ImageView(this);
            switchBtn.setImageResource(R.drawable.zd_ic_cam_switch);
            switchBtn.setScaleType(ImageView.ScaleType.CENTER);
            switchBtn.setBackground(circle(btnBg)); // giống hệt nút quay lại
            switchBtn.setImageTintList(ColorStateList.valueOf(0xCCFFFFFF));
            switchBtn.setContentDescription("Đổi camera");
            switchBtn.setVisibility(View.GONE);
            switchBtn.setOnClickListener(v -> { CallVideo cv = CallManager.get().video(); if (cv != null) cv.switchCamera(); });
            bar.addView(switchBtn, new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL));
        }
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

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
        status.setShadowLayer(4, 0, 1, 0x80000000);
        lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(6);
        root.addView(status, lp);

        if (videoMode) {
            // Một chạm trên hình: ẩn / hiện các nút và thông tin. Hai ngón trên hình bên kia: tách ra = toàn màn hình (cắt cho lấp đầy), chụm vào = về đúng tỉ lệ (có viền).
            android.view.ScaleGestureDetector pinch = new android.view.ScaleGestureDetector(this,
                    new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                        float total = 1f;
                        @Override public boolean onScaleBegin(android.view.ScaleGestureDetector d) { total = 1f; return true; }
                        @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                            total *= d.getScaleFactor();
                            if (!cropVideo && total > 1.15f) setCrop(true);
                            else if (cropVideo && total < 0.87f) setCrop(false);
                            return true;
                        }
                    });
            android.view.GestureDetector tap = new android.view.GestureDetector(this,
                    new android.view.GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(android.view.MotionEvent e) { return true; }
                        @Override public boolean onSingleTapUp(android.view.MotionEvent e) {
                            setControls(!controlsShown);
                            return true;
                        }
                    });
            remoteView.setOnTouchListener((v, ev) -> { pinch.onTouchEvent(ev); tap.onTouchEvent(ev); return true; });
            remoteView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> applyVideoTransform());
        }

        root.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1f));

        // Nút — thoại: Loa / Video / Kết thúc / Mic / Ghi âm; video: Loa / Camera / Kết thúc / Mic / Ghi âm (nút đỏ ở giữa,
        // nút Video của gọi thoại đúng chỗ nút Camera của gọi video).
        btnSize = 56;
        builtVideo = videoMode;
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        View.OnClickListener endClick = v -> {
            if (CallManager.get().busy()) CallManager.get().hangup(null);
            else finish();
        };
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
        } else { // mời chuyển sang gọi video: chỉ bấm được khi đã nối máy (gọi 1-1)
            videoReqBtn = roundButton(row, new ImageView(this), R.drawable.zd_ic_video, btnBg, "Video", v -> {
                CallManager m = CallManager.get();
                if (m.state() != CallManager.State.CONNECTED || m.isGroup()) {
                    Toast.makeText(this, "Chuyển sang video được khi đã nối máy", Toast.LENGTH_SHORT).show();
                    return;
                }
                withCamera(() -> {
                    CallManager.get().requestVideo();
                    Toast.makeText(this, "Đã mời " + m.partnerName() + " chuyển sang gọi video", Toast.LENGTH_SHORT).show();
                });
            });
        }
        roundButton(row, new ImageView(this), R.drawable.zd_ic_call_end, RED, "Kết thúc", endClick); // ở giữa hàng
        micIcon = new ImageView(this);
        micBtn = roundButton(row, micIcon, R.drawable.zd_ic_mic, btnBg, "Mic",
                v -> CallManager.get().setMuted(!CallManager.get().muted()));
        recIcon = new ImageView(this);
        recBtn = roundButton(row, recIcon, R.drawable.zd_ic_record, btnBg, "Ghi âm", v -> {
            if (CallManager.get().state() != CallManager.State.CONNECTED) {
                Toast.makeText(this, "Ghi âm được khi đã nối máy", Toast.LENGTH_SHORT).show();
                return;
            }
            CallManager.get().toggleRecording();
        });
        recLabel = (TextView) ((LinearLayout) recBtn.getParent()).getChildAt(1);
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
