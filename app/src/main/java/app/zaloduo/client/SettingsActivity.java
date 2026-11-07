package app.zaloduo.client;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Màn hình cài đặt. Mở bằng cách nhấn giữ biểu tượng app -> "Cài đặt",
 * hoặc nút "Cài đặt" trong thông báo chạy nền.
 */
public class SettingsActivity extends Activity {
    private LinearLayout list;
    private TextView status;

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (storageButton != null) storageButton.setText(storageLabel()); // vừa cấp quyền trong cài đặt hệ thống
    }

    /** Dòng trạng thái: phiên bản app, script trong trang Zalo có chạy không, đã nhận dạng bố cục chưa. */
    private void refreshStatus() {
        final String version = "ZaloDuo " + versionName();
        final String notif = "Thông báo: " + ("granted".equals(Notifier.permissionState(this)) ? "đã cho phép" : "CHƯA cho phép");
        status.setText(version + "\nĐang kiểm tra…");
        WebViewHolder.scriptStatus(s -> {
            String line;
            if (s == null) {
                line = "Zalo chưa mở";
            } else if ("none".equals(s)) {
                line = "Script trong trang Zalo: KHÔNG CHẠY";
            } else {
                int bar = s.indexOf('|');
                String v = bar > 0 ? s.substring(0, bar) : s;
                String ui;
                if (s.contains("\"enabled\":false")) ui = "tắt";
                else if (s.contains("\"detected\":true")) ui = "đã nhận dạng bố cục Zalo";
                else ui = "CHƯA nhận dạng được bố cục (bấm \"Sao chép cấu trúc giao diện\" và gửi đi)";
                line = "Script trong trang Zalo: đang chạy (" + v + ")\nGiao diện điện thoại: " + ui;
            }
            status.setText(version + "\n" + line + "\n" + notif);
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, pad, pad, pad);
        scroll.addView(list);
        setContentView(scroll);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackgroundColor(0xFFF1F4F8);
        list.addView(status);

        header("Thông báo & chạy nền");
        toggle("Chạy nền để nhận tin nhắn", Prefs.KEEP_ALIVE, true, on -> {
            if (on) KeepAliveService.start(this);
            else KeepAliveService.stop(this);
        });
        toggle("Tự chạy khi khởi động máy", Prefs.START_ON_BOOT, true, null);
        button("Cho phép chạy nền không giới hạn pin", () -> requestIgnoreBattery(this));
        // Huawei / Honor: "không giới hạn pin" chưa đủ, quản lý khởi chạy vẫn đóng băng app ở nền — phải bật thủ công.
        if (isHuawei()) button("Huawei: cho phép chạy nền (Quản lý khởi chạy)", this::openHuaweiLaunchManager);
        button("Cài đặt thông báo của app", this::openNotificationSettings);

        header("Cuộc gọi");
        toggle("Nhận cuộc gọi đến (thử nghiệm: báo với máy chủ Zalo app là Zalo PC — có thể làm thoát Zalo PC khác của tài khoản này)",
                Prefs.INCOMING_CALLS, true, on -> { if (on) CallManager.get().onPageLoaded(); });
        toggle("Giữ kết nối khi khoá màn hình (để nhận cuộc gọi khi máy khoá — tốn pin hơn: máy bị đánh thức mỗi 2 phút. "
                        + "Nên bật thêm \"Cho phép chạy nền không giới hạn pin\")",
                Prefs.KEEP_ON_LOCK, false, on -> {
                    if (on) {
                        KeepAliveService.start(this);
                        if (!isIgnoringBattery()) requestIgnoreBattery(this);
                    }
                });
        // Màn hình cuộc gọi đến toàn màn hình khi khoá máy: Android 14+ có thể rút quyền này (chỉ để cho app gọi điện /
        // báo thức); thiếu thì cuộc gọi đến chỉ hiện popup nhỏ, không bật màn hình.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
            boolean fsi = nm == null || nm.canUseFullScreenIntent();
            button(fsi ? "Màn hình cuộc gọi đến khi khoá máy: đã cho phép" : "Cho phép hiện cuộc gọi đến toàn màn hình khi khoá máy",
                    () -> safeStart(new Intent(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            android.net.Uri.parse("package:" + getPackageName()))));
        }
        toggle("Hiện thông số cuộc gọi (ping, tốc độ tiếng / hình) ở góc màn hình gọi", Prefs.CALL_STATS, true, null);
        ringtoneButton = button(ringtoneLabel(), this::pickRingtone);
        ringbackButton = button(ringbackLabel(), this::pickRingback);
        dropdown("Tự động ghi âm cuộc gọi",
                new String[]{"Tắt", "Mọi cuộc gọi", "Với người lạ", "Với người trong danh bạ"},
                Prefs.autoRecord(this), v -> Prefs.sp(this).edit().putInt(Prefs.AUTO_RECORD, v).apply());
        storageButton = button(storageLabel(), this::askStorage);
        button("Sao chép nhật ký cuộc gọi (gửi khi báo lỗi gọi)", () -> {
            String log = CallEvents.dump();
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("ZaloDuo call log", log));
            Toast.makeText(this, log.isEmpty() ? "Chưa có nhật ký cuộc gọi" : "Đã sao chép nhật ký cuộc gọi", Toast.LENGTH_LONG).show();
        });

        header("Hiển thị");
        toggle("Giao diện điện thoại (danh sách chat → chạm để mở chat)", Prefs.MOBILE_UI, true,
                on -> WebViewHolder.applySettings(true));
        dropdown("Giao diện (khi bật giao diện điện thoại)",
                new String[]{"Native — vẽ bằng app (mặc định)", "WebView — giao diện của Zalo Web"},
                Prefs.nativeList(this) ? 0 : 1, idx -> {
                    Prefs.put(this, Prefs.NATIVE_LIST, idx == 0);
                    WebViewHolder.applySettings(true); // màn chính tự dựng lại khi quay về (MainActivity.onResume)
                });
        slider("Độ đậm nền thanh dock", Prefs.dockOpacity(this), 100, "%", p -> {
            Prefs.put(this, Prefs.DOCK_OPACITY, p);
            WebViewHolder.applyDockSettings();
        });
        slider("Độ cao thanh dock (cách đáy màn hình)", Prefs.dockBottom(this), 80, "px", p -> {
            Prefs.put(this, Prefs.DOCK_BOTTOM, p);
            WebViewHolder.applyDockSettings();
        });
        // Nút zCloud, Công cụ của Zalo ở bên phải thanh dock (sau My Documents).
        toggle("Thanh dock: nút zCloud", Prefs.DOCK_CLOUD, false, on -> WebViewHolder.applyDockButtons());
        toggle("Thanh dock: nút Công cụ", Prefs.DOCK_TOOLS, false, on -> WebViewHolder.applyDockButtons());
        toggle("Tự ẩn thanh dock và thanh tìm kiếm khi cuộn (cuộn lên để hiện lại)", Prefs.DOCK_AUTO_HIDE, true,
                on -> WebViewHolder.applyDockSettings());
        choice("Bề rộng trang Zalo (khi tắt giao diện điện thoại)",
                new String[]{"Vừa màn hình điện thoại", "800 px", "980 px (như \"Trang web cho máy tính\")", "1280 px"},
                new int[]{0, 800, 980, 1280}, Prefs.VIEWPORT_WIDTH, Prefs.viewportWidth(this), true);
        choice("Cỡ chữ", new String[]{"Theo cỡ chữ của máy (" + Math.round(getResources().getConfiguration().fontScale * 100) + "%)",
                        "85%", "100%", "115%", "130%"},
                new int[]{0, 85, 100, 115, 130}, Prefs.TEXT_ZOOM, Prefs.textZoomSetting(this), false);

        header("Khác");
        // Ảnh / video tin nhắn đã lưu trong máy (MediaCache): dung lượng + xoá.
        button("Xóa ảnh/video đã lưu", () -> new AlertDialog.Builder(this)
                .setMessage("Xóa ảnh và video tin nhắn đã lưu trong máy? Lần sau xem lại sẽ phải tải lại.")
                .setPositiveButton("Xóa", (d, w) -> App.IO.execute(() -> {
                    MediaCache.clear(this);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Đã xóa", Toast.LENGTH_SHORT).show();
                        updateCacheLabel();
                    });
                }))
                .setNegativeButton("Hủy", null)
                .show());
        cacheButton = (Button) list.getChildAt(list.getChildCount() - 1);
        updateCacheLabel();
        button("Tải lại Zalo", () -> {
            WebViewHolder.reload();
            Toast.makeText(this, "Đang tải lại", Toast.LENGTH_SHORT).show();
        });
        // Công cụ gỡ lỗi: chỉ có ở bản debug, bản release (build trên GitHub) không hiện.
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            button("Sao chép cấu trúc giao diện (gửi để sửa lỗi hiển thị)", this::copyLayout);
            button("Sao chép log debug (gửi khi gặp lỗi)", this::copyDebugLog);
            button("Xóa log debug", () -> {
                DiagLog.clear();
                Toast.makeText(this, "Đã xóa nhật ký", Toast.LENGTH_SHORT).show();
            });
        }
        button("Đăng xuất & xóa dữ liệu Zalo trong app", this::confirmReset);
        note("Mẹo: nhấn giữ biểu tượng app để mở nhanh màn hình này.\n"
                + "Phiên bản " + versionName());
    }

    // ------------------------------------------------------------------ hành động

    private boolean isIgnoringBattery() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    static void requestIgnoreBattery(Context c) {
        try {
            c.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + c.getPackageName())));
        } catch (ActivityNotFoundException e) {
            try {
                c.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
            }
        }
    }

    private static boolean isHuawei() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.toLowerCase(java.util.Locale.ROOT);
        return m.contains("huawei") || m.contains("honor");
    }

    /**
     * Màn "Khởi chạy ứng dụng" của Huawei (tên lớp đổi theo bản EMUI / HarmonyOS): người dùng tắt "Quản lý tự động" của
     * ZaloDuo, bật đủ Tự khởi chạy / Khởi chạy phụ / Chạy nền. Không mở được thì mở thông tin app.
     */
    private void openHuaweiLaunchManager() {
        String[][] targets = {
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
                {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
        };
        for (String[] t : targets) {
            try {
                startActivity(new Intent().setClassName(t[0], t[1]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Toast.makeText(this, "Tìm ZaloDuo, tắt \"Quản lý tự động\", bật cả Tự khởi chạy, Khởi chạy phụ, Chạy nền",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (RuntimeException ignored) {
            }
        }
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void openNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        safeStart(i);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("Đăng xuất Zalo?")
                .setMessage("Xóa phiên đăng nhập, bộ nhớ đệm và dữ liệu Zalo Web trong app. "
                        + "Tin nhắn vẫn còn trên điện thoại chính.")
                .setPositiveButton("Đăng xuất", (d, w) -> WebViewHolder.resetAll(this, () ->
                        Toast.makeText(this, "Đã xóa. Quét mã QR để đăng nhập lại.", Toast.LENGTH_LONG).show()))
                .setNegativeButton("Hủy", null)
                .show();
    }

    /** Chỉ chép khung giao diện (loại thẻ, class, vị trí, kích thước) — không có nội dung tin nhắn hay tên. */
    private void copyLayout() {
        WebViewHolder.dumpLayout(text -> {
            if (text == null || text.isEmpty()) {
                Toast.makeText(this, "Zalo chưa tải xong, mở app rồi thử lại", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("ZaloDuo layout", text));
            Toast.makeText(this, "Đã sao chép, dán vào cuộc trò chuyện để gửi", Toast.LENGTH_LONG).show();
        });
    }

    /**
     * Log debug để gửi khi gặp lỗi: cài đặt của app, quyền, trạng thái trang Zalo lúc bấm, nhật ký sự kiện (app vào
     * nền, Doze, mạng, trang tải lại, kết nối, thông báo...), các lần tiến trình bị tắt và lỗi crash gần nhất.
     * Không có tên người hay nội dung tin nhắn.
     */
    private void copyDebugLog() {
        WebViewHolder.debugState(page -> {
            StringBuilder sb = new StringBuilder("=== ZaloDuo debug log ===\n");
            PowerManager pm = getSystemService(PowerManager.class);
            sb.append("settings: keepAlive=").append(Prefs.keepAlive(this))
                    .append(" startOnBoot=").append(Prefs.startOnBoot(this))

                    .append(" mobileUi=").append(Prefs.mobileUi(this))
                    .append(" viewport=").append(Prefs.viewportWidth(this))
                    .append(" textZoom=").append(Prefs.textZoom(this)).append(Prefs.textZoomSetting(this) == 0 ? "(device)" : "")
                    .append(" dock(opacity=").append(Prefs.dockOpacity(this))
                    .append(" bottom=").append(Prefs.dockBottom(this))
                    .append(" autoHide=").append(Prefs.dockAutoHide(this)).append(")\n");
            sb.append("permissions: notifications=").append(Notifier.permissionState(this))
                    .append(" batteryUnrestricted=").append(pm != null && pm.isIgnoringBatteryOptimizations(getPackageName()))
                    .append(" nightMode=").append((getResources().getConfiguration().uiMode
                            & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                            == android.content.res.Configuration.UI_MODE_NIGHT_YES)
                    .append('\n');
            sb.append("page: ").append(page == null ? "zalo not open" : page).append('\n');
            sb.append(DiagLog.dump(this));
            if (CrashLog.hasPending(this)) sb.append("--- last crash\n").append(CrashLog.read(this)).append('\n');
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("ZaloDuo debug log", sb.toString()));
            Toast.makeText(this, "Đã sao chép log debug, dán vào tin nhắn để gửi", Toast.LENGTH_LONG).show();
        });
    }

    private void safeStart(Intent i) {
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Máy không hỗ trợ màn hình này", Toast.LENGTH_SHORT).show();
        }
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ dựng giao diện

    private interface OnToggle {
        void changed(boolean on);
    }

    private void header(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(0xFF0068FF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(20), 0, dp(8));
        list.addView(t);
    }

    private void toggle(String label, final String key, boolean def, final OnToggle onToggle) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setPadding(0, dp(10), 0, dp(10));
        s.setChecked(Prefs.bool(this, key, def));
        s.setOnCheckedChangeListener((b, on) -> {
            Prefs.put(this, key, on);
            if (onToggle != null) onToggle.changed(on);
        });
        list.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void choice(String label, String[] names, final int[] values, final String key, int current,
                        final boolean reload) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(10), 0, dp(4));
        list.addView(t);
        RadioGroup g = new RadioGroup(this);
        for (int i = 0; i < names.length; i++) {
            RadioButton r = new RadioButton(this);
            r.setId(1000 + i);
            r.setText(names[i]);
            r.setChecked(values[i] == current);
            g.addView(r);
        }
        g.setOnCheckedChangeListener((group, checkedId) -> {
            int idx = checkedId - 1000;
            if (idx < 0 || idx >= values.length) return;
            Prefs.put(this, key, values[idx]);
            WebViewHolder.applySettings(reload);
        });
        list.addView(g);
    }

    private interface OnValue {
        void changed(int value);
    }

    /** Nhãn + danh sách thả xuống; báo chỉ số mục được chọn (không báo lúc dựng). */
    private void dropdown(String label, String[] names, int current, final OnValue onChange) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(10), 0, dp(4));
        list.addView(t);
        android.widget.Spinner s = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(current, false);
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private int last = current;

            @Override
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                if (pos == last) return;
                last = pos;
                onChange.changed(pos);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        list.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Thanh kéo 0-max, nhãn hiện giá trị hiện tại kèm đơn vị; áp dụng ngay khi kéo. */
    private void slider(final String label, int current, int max, final String unit, final OnValue onChange) {
        final TextView t = new TextView(this);
        t.setText(label + ": " + current + unit);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(10), 0, dp(4));
        list.addView(t);
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setProgress(current);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                t.setText(label + ": " + progress + unit);
                onChange.changed(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        list.addView(s, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private Button cacheButton;

    /** Nhãn nút xóa kèm dung lượng ảnh/video đã lưu (đếm ở luồng nền). */
    private void updateCacheLabel() {
        App.IO.execute(() -> {
            long b = MediaCache.size(this);
            String s = b < 1024 * 1024 ? (b / 1024) + " KB" : String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1024f / 1024f);
            runOnUiThread(() -> { if (cacheButton != null) cacheButton.setText("Xóa ảnh/video đã lưu (" + s + ")"); });
        });
    }

    // ------------------------------------------------------------------ nhạc chuông cuộc gọi đến

    private static final int REQ_RINGTONE = 77;
    private Button ringtoneButton;

    private String ringtoneLabel() {
        android.net.Uri u = Prefs.ringtone(this);
        String name;
        if (u == null) name = "Im lặng (chỉ rung)";
        else if (!Prefs.hasCustomRingtone(this)) name = "Mặc định của máy";
        else {
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(this, u);
            name = r != null ? r.getTitle(this) : u.getLastPathSegment();
        }
        return "Nhạc chuông cuộc gọi đến: " + name;
    }

    private void pickRingtone() {
        android.net.Uri cur = Prefs.ringtone(this);
        android.content.Intent i = new android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Nhạc chuông cuộc gọi đến")
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, android.provider.Settings.System.DEFAULT_RINGTONE_URI)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                        Prefs.hasCustomRingtone(this) ? cur : android.provider.Settings.System.DEFAULT_RINGTONE_URI);
        try {
            startActivityForResult(i, REQ_RINGTONE);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "Máy không có trình chọn nhạc chuông", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ thư mục ZaloDuo (ghi âm, ảnh, video)

    private Button storageButton;

    private String storageLabel() {
        if (Storage.canWriteRoot(this)) return "Ghi âm, ảnh, video lưu tại: Bộ nhớ trong/ZaloDuo";
        if (android.os.Build.VERSION.SDK_INT == 29) return "Ghi âm, ảnh, video lưu tại: Download/ZaloDuo (Android 10)";
        return "Cho phép lưu vào Bộ nhớ trong/ZaloDuo (đang lưu ở " + Storage.base(this) + ")";
    }

    /** Android 11+: trang "Truy cập mọi tệp" của app; Android 9 trở xuống: hỏi quyền bộ nhớ. */
    private void askStorage() {
        if (Storage.canWriteRoot(this) || android.os.Build.VERSION.SDK_INT == 29) return;
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            safeStart(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } else {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 79);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == 79 && storageButton != null) storageButton.setText(storageLabel());
    }

    // ------------------------------------------------------------------ hồi chuông khi gọi đi

    private static final int REQ_RINGBACK = 78;
    private Button ringbackButton;

    private String ringbackLabel() {
        android.net.Uri u = Prefs.ringback(this);
        String name;
        if (u == null) name = "Im lặng";
        else if (!Prefs.hasCustomRingback(this)) name = "Mặc định của app";
        else {
            android.media.Ringtone r = android.media.RingtoneManager.getRingtone(this, u);
            name = r != null ? r.getTitle(this) : u.getLastPathSegment();
        }
        return "Hồi chuông khi gọi đi: " + name;
    }

    /** Trình chọn nhạc của máy (có "Thêm nhạc chuông" để chọn file riêng); mục "Mặc định" = hồi chuông của app. */
    private void pickRingback() {
        android.net.Uri def = Prefs.defaultRingback(this), cur = Prefs.ringback(this);
        android.content.Intent i = new android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_ALL)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Hồi chuông khi gọi đi")
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, def)
                .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur == null ? null : cur);
        try {
            startActivityForResult(i, REQ_RINGBACK);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "Máy không có trình chọn nhạc chuông", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        android.net.Uri u = data.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (req == REQ_RINGTONE) {
            Prefs.setRingtone(this, u);
            if (ringtoneButton != null) ringtoneButton.setText(ringtoneLabel());
        } else if (req == REQ_RINGBACK) {
            Prefs.setRingback(this, u);
            if (ringbackButton != null) ringbackButton.setText(ringbackLabel());
        }
    }

    private Button button(String label, final Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        list.addView(b, lp);
        return b;
    }

    private void note(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(0xFF666666);
        t.setPadding(0, dp(24), 0, 0);
        list.addView(t);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
