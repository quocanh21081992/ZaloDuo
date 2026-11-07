package app.zaloduo.client;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Báo hiệu cuộc gọi qua trang Zalo Web: gọi window.__zw.callSig(reqId, op, args) (shim mục 19), trang tự mã hoá tham số
 * bằng khoá phiên và gửi tới voicecall-wpa; kết quả quay về JsBridge.callSigResult -> {@link #onResult}.
 */
final class CallSignal {
    interface Callback {
        /** ok=false: lỗi mạng/trang; body là phản hồi đã giải mã ({error_code, data}) hoặc {_err}. Chạy trên luồng nền. */
        void done(boolean ok, JSONObject body);
    }

    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final Map<String, Callback> PENDING = new HashMap<>();
    private static final long TIMEOUT_MS = 15000;

    private CallSignal() {}

    static void call(String op, JSONArray args, Callback cb) {
        final String id = "s" + SEQ.getAndIncrement();
        synchronized (PENDING) { PENDING.put(id, cb); }
        CallEvents.log("sig -> " + op + " " + args);
        String js = "window.__zw&&window.__zw.callSig&&window.__zw.callSig(" + JSONObject.quote(id) + ","
                + JSONObject.quote(op) + "," + JSONObject.quote(args.toString()) + ")";
        App.MAIN.post(() -> WebViewHolder.evalMain(js));
        App.MAIN.postDelayed(() -> {
            Callback c;
            synchronized (PENDING) { c = PENDING.remove(id); }
            if (c != null) {
                CallEvents.log("sig timeout " + op);
                App.IO.execute(() -> c.done(false, errBody("timeout")));
            }
        }, TIMEOUT_MS);
    }

    /** Từ JsBridge (luồng WebView). */
    static void onResult(String id, boolean ok, String json) {
        Callback c;
        synchronized (PENDING) { c = PENDING.remove(id); }
        CallEvents.log("sig <- " + id + " ok=" + ok + " " + (json.length() > 30000 ? json.substring(0, 30000) : json));
        if (c == null) return;
        JSONObject body;
        try {
            body = new JSONObject(json);
        } catch (Exception e) {
            body = errBody("bad json");
            ok = false;
        }
        final boolean fOk = ok;
        final JSONObject fBody = body;
        App.IO.execute(() -> c.done(fOk, fBody));
    }

    private static JSONObject errBody(String msg) {
        JSONObject o = new JSONObject();
        try { o.put("_err", msg); } catch (Exception ignored) { }
        return o;
    }

    /** Phản hồi thành công của máy chủ (error_code 0): trả về phần data, không thì null. */
    static JSONObject data(boolean ok, JSONObject body) {
        if (!ok || body == null || body.optInt("error_code", -1) != 0) return null;
        JSONObject d = body.optJSONObject("data");
        // voicecall trả {error_code, data:{error_code, error_message, data:{...}}}: bóc tới lớp trong cùng.
        while (d != null && d.has("error_code") && d.optJSONObject("data") != null) {
            if (d.optInt("error_code", 0) != 0) return null;
            d = d.optJSONObject("data");
        }
        if (d != null && d.has("error_code") && d.optInt("error_code", 0) != 0) return null;
        return d != null ? d : new JSONObject();
    }

    static String describe(JSONObject body) {
        if (body == null) return "không có phản hồi";
        if (body.has("_err")) return body.optString("_err");
        JSONObject in = body.optJSONObject("data");
        if (body.optInt("error_code", -1) == 0 && in != null && in.has("error_code")) return describe(in);
        return "mã " + body.optInt("error_code", -1) + (body.optString("error_message").isEmpty() ? "" : ": " + body.optString("error_message"));
    }
}
