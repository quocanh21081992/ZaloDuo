package app.zaloduo.client;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/**
 * Khung chat trong bong bóng (Android 11+): chính màn hình Zalo của {@link MainActivity}, mở sẵn một hội thoại.
 * Chỉ có một WebView Zalo cho cả app: bong bóng mở ra thì lấy WebView về đây, thu bong bóng lại thì trả về
 * (đỗ ở màn hình ảo cho trang vẫn chạy nhận tin, MainActivity mở lên sẽ lấy lại — xem {@link WebViewHolder#detach}).
 */
public class BubbleActivity extends MainActivity {

    static Intent intent(Context c, String convId) {
        return new Intent(c, BubbleActivity.class)
                // Mỗi hội thoại một bong bóng riêng: data khác nhau để hệ thống không gộp các intent.
                .setData(Uri.parse("zaloduo://conv/" + Uri.encode(convId)))
                .putExtra(EXTRA_CONV, convId);
    }

    @Override
    boolean isBubble() {
        return true;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Bong bóng thu lại: cửa sổ bị ẩn, WebView còn gắn ở đây sẽ bị Chromium đóng băng -> trả về chỗ đỗ.
        WebViewHolder.detach(this);
        MainActivity.returnWebViewToMain();
    }
}
