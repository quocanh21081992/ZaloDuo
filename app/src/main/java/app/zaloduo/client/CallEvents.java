package app.zaloduo.client;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sự kiện điều khiển cuộc gọi (khung WebSocket 601–604 của Zalo, shim mục 19) và nhật ký cuộc gọi: logcat (tag
 * ZaloDuoCall) và tệp call.log trong bộ nhớ riêng của app (tối đa ~512 KB, cũ thì xoay vòng). Có mã người dùng, địa chỉ
 * máy chủ trung chuyển; không có nội dung tin nhắn. Sự kiện act_type "voip" được chuyển cho {@link CallManager}.
 */
final class CallEvents {
    static final String TAG = "ZaloDuoCall";
    private static final long MAX = 512 * 1024;
    private static File file;

    private CallEvents() {}

    static synchronized void init(Context c) {
        if (file == null) file = new File(c.getFilesDir(), "call.log");
    }

    /** Phần cuối nhật ký cuộc gọi (~150 KB gần nhất) để sao chép gửi khi báo lỗi. */
    static synchronized String dump() {
        if (file == null || !file.exists()) return "";
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(file, "r")) {
            long len = f.length(), from = Math.max(0, len - 150_000);
            byte[] b = new byte[(int) (len - from)];
            f.seek(from);
            f.readFully(b);
            return new String(b, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    static synchronized File filesDir() {
        return file == null ? null : file.getParentFile();
    }

    /** Từ JsBridge (luồng WebView). dir: "rx" nhận / "tx" gửi; ws: số thứ tự WebSocket trong shim. */
    static void onFrame(String dir, int ws, int cmd, int sub, String body) {
        if ("rx".equals(dir) && cmd >= 601 && cmd <= 604) dispatchVoip(body);
        // Khung điều khiển khác (vào/rời nhóm...) không liên quan cuộc gọi: chỉ ghi khung có voip hoặc AUTHEN.
        if (cmd == 1 || "tx".equals(dir) || body.contains("\"voip\"") || body.contains("_err")) {
            log(dir + " ws#" + ws + " cmd=" + cmd + " sub=" + sub + " " + body);
        }
    }

    private static void dispatchVoip(String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONObject d = o.optJSONObject("data");
            JSONArray controls = d != null ? d.optJSONArray("controls") : null;
            if (controls == null) return;
            for (int i = 0; i < controls.length(); i++) {
                JSONObject content = controls.optJSONObject(i) != null ? controls.optJSONObject(i).optJSONObject("content") : null;
                if (content == null || !"voip".equals(content.optString("act_type"))) continue;
                JSONObject data;
                Object raw = content.opt("data");
                if (raw instanceof JSONObject) data = (JSONObject) raw;
                else {
                    try { data = new JSONObject(String.valueOf(raw)); } catch (Exception e) { data = new JSONObject(); }
                }
                CallManager.get().onVoipEvent(content.optString("act"), data);
            }
        } catch (Exception e) {
            Log.w(TAG, "dispatchVoip", e);
        }
    }

    static void log(String line) {
        // logcat cắt dòng ~4 KB: chia nhỏ để đọc lại được trọn.
        for (int i = 0; i < line.length(); i += 3000) {
            Log.i(TAG, (i == 0 ? "" : "  ...") + line.substring(i, Math.min(line.length(), i + 3000)));
        }
        append(line);
    }

    private static synchronized void append(String line) {
        if (file == null) return;
        try {
            if (file.length() > MAX) {
                File old = new File(file.getPath() + ".old");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                file.renameTo(old);
            }
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write((ts + " " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "append", e);
        }
    }
}
