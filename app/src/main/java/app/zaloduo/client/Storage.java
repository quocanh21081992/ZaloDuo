package app.zaloduo.client;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Thư mục của ZaloDuo trong bộ nhớ máy: Bộ nhớ trong/ZaloDuo/records (ghi âm cuộc gọi), /pictures (ảnh tải về),
 * /videos (video tải về).
 * <p>
 * Thư mục ở gốc bộ nhớ: Android 11+ chỉ ghi được khi app có quyền "Truy cập mọi tệp" (người dùng bật trong Cài đặt →
 * Lưu tệp); Android 9 trở xuống cần quyền bộ nhớ. Chưa có quyền (và Android 10, không có cách nào) thì lưu tạm vào
 * Download/ZaloDuo/… — không cần quyền, không mất tệp.
 */
final class Storage {
    private Storage() {}

    static final String ROOT = "ZaloDuo";
    static final String RECORDS = "records", PICTURES = "pictures", VIDEOS = "videos";

    /** Ghi thẳng vào Bộ nhớ trong/ZaloDuo được không. */
    static boolean canWriteRoot(Context c) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT == 29) return false;
        return c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Nơi đang lưu (để hiện trong Cài đặt / thông báo): "ZaloDuo" hoặc "Download/ZaloDuo". */
    static String base(Context c) {
        return canWriteRoot(c) ? ROOT : Environment.DIRECTORY_DOWNLOADS + "/" + ROOT;
    }

    /** Ảnh -> pictures, video -> videos; loại khác: null (giữ chỗ lưu cũ Download/Zalo). */
    static String kindOf(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(java.util.Locale.ROOT);
        if (m.isEmpty() || m.equals("application/octet-stream")) {
            String g = java.net.URLConnection.guessContentTypeFromName(name == null ? "" : name.toLowerCase(java.util.Locale.ROOT));
            if (g != null) m = g;
        }
        if (m.startsWith("image/")) return PICTURES;
        if (m.startsWith("video/")) return VIDEOS;
        return null;
    }

    /**
     * Lưu dữ liệu từ {@code in} thành tệp {@code name} trong ZaloDuo/{@code kind}. Trả về đường dẫn hiển thị
     * (vd. "ZaloDuo/pictures/a.jpg"). Luồng nền.
     */
    static String save(Context c, String kind, String name, String mime, InputStream in) throws IOException {
        String type = mime == null || mime.isEmpty() ? "application/octet-stream" : mime;
        if (canWriteRoot(c)) {
            File dir = new File(new File(Environment.getExternalStorageDirectory(), ROOT), kind);
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs " + dir);
            File f = Downloads.unique(dir, name);
            copy(in, new FileOutputStream(f));
            MediaScannerConnection.scanFile(c, new String[]{f.getAbsolutePath()}, new String[]{type}, null);
            return ROOT + "/" + kind + "/" + f.getName();
        }
        if (Build.VERSION.SDK_INT >= 29) {
            String rel = Environment.DIRECTORY_DOWNLOADS + "/" + ROOT + "/" + kind;
            ContentResolver cr = c.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, type);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, rel);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) throw new IOException("MediaStore insert failed");
            OutputStream os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("openOutputStream failed");
            copy(in, os);
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, v, null, null);
            return rel + "/" + name;
        }
        // Android 9 trở xuống, chưa cho quyền bộ nhớ: thư mục riêng của app (không cần quyền).
        File dir = new File(c.getExternalFilesDir(null), ROOT + "/" + kind);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdirs " + dir);
        File f = Downloads.unique(dir, name);
        copy(in, new FileOutputStream(f));
        return f.getAbsolutePath();
    }

    static void copy(InputStream in, OutputStream out) throws IOException {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }
}
