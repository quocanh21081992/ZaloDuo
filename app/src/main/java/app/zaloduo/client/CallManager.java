package app.zaloduo.client;

import android.content.Context;
import android.media.AudioManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cuộc gọi thoại 1-1 gọi đi (giai đoạn 1). Luồng theo ctdozlite (call.rs) và chính các hàm của Zalo Web:
 * <ol>
 * <li>requestCall (401, /api/voicecall/requestcall): máy chủ trả callId, fromId/toId, sessId và danh sách relay.</li>
 * <li>{@link ZrtpRelay#start}: InitZRTP + Echo tới các relay, lấy token và địa chỉ công khai.</li>
 * <li>sendRequestCall (416, /api/voicecall/request): gửi codec Opus + extendData (relay, p2p) — máy người nhận đổ chuông.</li>
 * <li>Sự kiện WebSocket 601 act_type voip: ring_ring (đang đổ chuông), answer status 0 (nghe máy, params.rtpSerIp = relay
 * dùng cho media), end_call / cancel (bên kia tắt).</li>
 * <li>Nghe máy: answerack (408), bật âm thanh Opus/RTP qua relay. Tắt: endcall (409) hoặc cancel (405) nếu chưa nối.</li>
 * </ol>
 * Gọi đến chưa hỗ trợ: phiên Zalo Web không nhận được lời mời (hàng đợi 604 chỉ dành cho Zalo PC).
 */
final class CallManager {
    enum State { IDLE, DIALING, CALLING, RINGING, INCOMING, CONNECTED, ENDED }

    interface Listener {
        /** Luồng chính. */
        void onCallState(State state, String detail);
    }

    private static final CallManager INSTANCE = new CallManager();
    private static final long NO_ANSWER_MS = 60_000;
    /** Bitrate tối đa báo cho bên gửi hình (REMB): đủ cho 720p 30 fps. */
    private static final int REMB_BPS = 2_500_000;
    static final String CODEC = "[{\"dynamicFptime\":0,\"frmPtime\":20,\"name\":\"opus/16000/1\",\"payload\":112}]\n";

    static CallManager get() { return INSTANCE; }

    private Context app;
    private Listener listener;
    private volatile State state = State.IDLE;
    private String detail = "";
    private String partnerId = "", partnerName = "", partnerAvatar = "";
    private long callId;
    private int fromUid, partnerUid;
    private String sessId = "";
    private ZrtpRelay relay;
    private CallAudio audio;
    private CallVideo video;
    private boolean isVideo;
    private long connectedAt;
    private boolean muted;
    private android.graphics.Bitmap avatarBmp;
    private int gen; // tăng mỗi cuộc gọi: bỏ qua kết quả trễ của cuộc trước

    private CallManager() {}

    synchronized State state() { return state; }
    synchronized String detail() { return detail; }
    synchronized String partnerName() { return partnerName; }
    synchronized String partnerAvatar() { return partnerAvatar; }
    synchronized long connectedAt() { return connectedAt; }
    synchronized boolean muted() { return muted; }
    boolean speaker() { return KeepAliveService.isSpeakerOn(); }
    synchronized android.graphics.Bitmap avatarBitmap() { return avatarBmp; }

    /** Ảnh đại diện đã tải (CallActivity) — cho thông báo cuộc gọi. */
    void setAvatarBitmap(android.graphics.Bitmap b) {
        synchronized (this) { avatarBmp = b; }
        App.MAIN.post(KeepAliveService::refreshNotification);
    }

    /** Trạng thái hiển thị đổi từ nơi khác (nút loa trong thông báo). */
    void refresh() { notifyListener(); }
    boolean busy() { State s = state; return s != State.IDLE && s != State.ENDED; }

    void setListener(Listener l) {
        listener = l;
        if (l != null) { State s; String d; synchronized (this) { s = state; d = detail; } l.onCallState(s, d); }
    }

    // ------------------------------------------------------------------ gọi đi

    void startOutgoing(Context c, String uid, String name, String avatar, boolean withVideo) {
        final int myGen;
        synchronized (this) {
            if (busy()) return;
            app = c.getApplicationContext();
            incoming = false;
            group = false;
            isVideo = withVideo;
            video = null;
            partnerId = uid;
            partnerName = name == null || name.isEmpty() ? uid : name;
            partnerAvatar = avatar == null ? "" : avatar;
            avatarBmp = null;
            callId = (System.currentTimeMillis() % 1_800_000_000L) + 100_000_000L;
            fromUid = partnerUid = 0;
            sessId = "";
            relay = null;
            audio = null;
            connectedAt = 0;
            muted = false;
            myGen = ++gen;
        }
        setState(State.DIALING, "Đang kết nối…");
        App.MAIN.post(() -> KeepAliveService.setMediaActive(app, true, withVideo));
        CallEvents.log("call start uid=" + uid + " callId=" + callId + " video=" + withVideo);
        // typeRequest: 1 thoại, 3 video (ZaloCall.exe qua ctdozlite)
        JSONArray args = new JSONArray().put(uid).put(callId).put("[]").put(withVideo ? 3 : 1);
        CallSignal.call("requestCall", args, (ok, body) -> {
            if (!current(myGen)) return;
            JSONObject d = CallSignal.data(ok, body);
            if (d == null) { fail("Không gọi được (" + CallSignal.describe(body) + ")"); return; }
            int st = d.optInt("status", 0);
            if (st != 0) { fail(msgOr(d, "Người nhận không thể nhận cuộc gọi lúc này") + " (" + st + ")"); return; }
            onRequestCallOk(myGen, d);
        });
    }

    private void onRequestCallOk(int myGen, JSONObject d) {
        ZrtpRelay r;
        synchronized (this) {
            callId = d.optLong("id", callId);
            fromUid = (int) parseLong(d.opt("fromId") != null ? d.opt("fromId") : d.opt("callerId"));
            partnerUid = (int) parseLong(d.opt("toId") != null ? d.opt("toId") : d.opt("calleeId"));
            if (partnerUid == 0) partnerUid = (int) parseLong(partnerId);
            sessId = firstNonEmpty(d.optString("sessId"), d.optString("session"),
                    d.optJSONObject("callSetting") != null ? d.optJSONObject("callSetting").optString("session") : "");
            r = relay = new ZrtpRelay(fromUid, partnerUid, (int) callId, true, sessId);
        }
        collectServers(r, d);
        CallEvents.log("401 ok callId=" + callId + " from=" + Integer.toUnsignedString(fromUid)
                + " to=" + Integer.toUnsignedString(partnerUid) + " servers=" + r.servers.size());
        if (r.servers.isEmpty()) { fail("Máy chủ không cấp đường truyền cuộc gọi"); return; }
        try {
            r.start(300);
        } catch (Exception e) {
            fail("Không mở được kết nối mạng (" + e.getMessage() + ")");
            return;
        }
        if (!current(myGen)) return;
        JSONObject ext = buildExtendData(r, d, isVideo);
        ZrtpRelay.Server sel = r.firstOk();
        String rtp = sel != null ? sel.rtp : r.servers.get(0).rtp;
        String rtcp = sel != null ? sel.rtcp : r.servers.get(0).rtcp;
        JSONArray args = new JSONArray().put(partnerId).put(rtcp).put(rtp).put(CODEC).put(ext.toString() + "\n")
                .put(sessId).put(callId);
        CallSignal.call("sendRequestCall", args, (ok, body) -> {
            if (!current(myGen)) return;
            JSONObject rd = CallSignal.data(ok, body);
            if (rd == null) { fail("Không đổ chuông được (" + CallSignal.describe(body) + ")"); return; }
            int st = rd.optInt("status", 0);
            if (st != 0) { fail(msgOr(rd, "Không đổ chuông được tới người nhận") + " (" + st + ")"); return; }
            setState(State.CALLING, "Đang gọi…");
            App.MAIN.postDelayed(() -> {
                if (current(myGen) && (state == State.CALLING || state == State.RINGING)) {
                    CallEvents.log("no answer timeout");
                    hangup("Không trả lời");
                }
            }, NO_ANSWER_MS);
        });
    }

    // ------------------------------------------------------------------ gọi nhóm (chưa thử được: cần nhóm test)

    /*
     * Theo ctdozlite (call.rs nhánh is_group, chưa từng chạy với nhóm của mình): 12433 group/requestcall {groupId, callId,
     * typeRequest 0 = thoại, data {groupName, groupAvatar, maxUsers, noiseId = thành viên}, partners} -> máy chủ trả
     * callId, hostCall, session, relay (SFU), toId = những người được gọi. Thăm dò relay bằng gói ZRTP nhóm, rồi 12434
     * group/request {calleeId = hostCall, data {codec, extendData, rtp/rtcpAddress, data = meta}, session, partners,
     * groupId}. Ai nghe máy: act "group_answer" (fromId); ai rời: "group_end_call". Tắt: 12438 endcall (đã có người
     * nghe) / 12437 cancel.
     */
    private boolean group;
    private String groupId = "", groupMembers = "[]";
    private long hostCall;
    private final java.util.Set<Long> groupActive = new java.util.HashSet<>(), groupPending = new java.util.HashSet<>();

    synchronized boolean isGroup() { return group; }

    /** Số người đang trong cuộc gọi nhóm (không tính mình). */
    synchronized int groupActiveCount() { return groupActive.size(); }

    void startGroup(Context c, String gid, String name, String avatar, String membersJson) {
        final int myGen;
        final JSONArray members;
        try { members = new JSONArray(membersJson == null || membersJson.isEmpty() ? "[]" : membersJson); } catch (Exception e) { return; }
        synchronized (this) {
            if (busy()) return;
            app = c.getApplicationContext();
            incoming = false;
            isVideo = false;
            group = true;
            video = null;
            audio = null;
            relay = null;
            groupId = gid;
            groupMembers = members.toString();
            groupActive.clear();
            groupPending.clear();
            partnerId = "g" + gid;
            partnerName = name == null || name.isEmpty() ? "Nhóm" : name;
            partnerAvatar = avatar == null ? "" : avatar;
            avatarBmp = null;
            callId = (System.currentTimeMillis() % 1_800_000_000L) + 100_000_000L;
            connectedAt = 0;
            muted = false;
            myGen = ++gen;
        }
        setState(State.DIALING, "Đang kết nối…");
        App.MAIN.post(() -> KeepAliveService.setMediaActive(app, true, false));
        CallEvents.log("group call start gid=" + gid + " members=" + members.length() + " callId=" + callId);
        requestGroup(myGen, members, true);
    }

    private JSONObject groupMeta(JSONArray members, boolean withHost) throws Exception {
        JSONObject o = new JSONObject().put("extraData", "").put("groupAvatar", partnerAvatar);
        if (!withHost) o.put("groupId", groupId);
        o.put("groupName", partnerName);
        if (withHost) o.put("hostCall", hostCall);
        return o.put("maxUsers", 8).put("noiseId", members);
    }

    private void requestGroup(int myGen, JSONArray members, boolean mayRetry) {
        JSONArray args;
        try {
            args = new JSONArray().put(groupId).put(callId).put(0).put(groupMeta(members, false).toString()).put(members.toString());
        } catch (Exception e) { fail("Lỗi dữ liệu nhóm"); return; }
        CallSignal.call("requestCallGroup", args, (ok, body) -> {
            if (!current(myGen)) return;
            JSONObject d = CallSignal.data(ok, body);
            if (d == null) { fail("Không gọi nhóm được (" + CallSignal.describe(body) + ")"); return; }
            int st = d.optInt("status", 0);
            if (st == 2 && mayRetry) {
                // ctdozlite: status 2 = phiên chưa là Zalo PC -> nâng phiên rồi thử lại.
                CallSignal.call("upgradeSession", new JSONArray(), (ok2, b2) -> requestGroup(myGen, members, false));
                return;
            }
            if (st != 0) { fail(msgOr(d, "Nhóm không thể nhận cuộc gọi lúc này") + " (" + st + ")"); return; }
            onGroupRequested(myGen, d, members);
        });
    }

    private void onGroupRequested(int myGen, JSONObject d, JSONArray members) {
        // params (chuỗi JSON) bổ sung các trường còn trống
        try {
            String ps = d.optString("params", "");
            if (ps.trim().startsWith("{")) {
                JSONObject p = new JSONObject(ps);
                for (java.util.Iterator<String> it = p.keys(); it.hasNext(); ) {
                    String k = it.next();
                    if (!d.has(k) || d.isNull(k) || "".equals(d.opt(k))) d.put(k, p.get(k));
                }
            }
        } catch (Exception ignored) { }
        JSONArray pids = d.optJSONArray("partnerIds");
        for (int i = 0; pids != null && i < pids.length(); i++) {
            String u = pids.optString(i);
            boolean have = false;
            for (int j = 0; j < members.length(); j++) if (u.equals(members.optString(j))) have = true;
            if (!u.isEmpty() && !have) members.put(u);
        }
        ZrtpRelay r;
        synchronized (this) {
            callId = parseLong(d.opt("callId") != null ? d.opt("callId") : d.opt("id"));
            if (callId == 0) callId = d.optLong("id", callId);
            hostCall = parseLong(d.opt("hostCall") != null ? d.opt("hostCall") : d.opt("fromId"));
            fromUid = (int) hostCall;
            groupMembers = members.toString();
            JSONArray to = d.optJSONArray("toId");
            for (int i = 0; to != null && i < to.length(); i++) {
                long u = parseLong(to.opt(i));
                if (u != 0 && u != hostCall) groupPending.add(u);
            }
            partnerUid = groupPending.isEmpty() ? (int) hostCall : (int) (long) groupPending.iterator().next();
            JSONObject cs = d.optJSONObject("callSetting");
            sessId = firstNonEmpty(cs != null ? cs.optString("session") : "", d.optString("session"), d.optString("sessId"));
            r = relay = new ZrtpRelay(fromUid, partnerUid, (int) callId, true, sessId);
            r.setGroup((int) parseLong(firstNonEmpty(d.optString("groupId"), groupId)));
        }
        collectServers(r, d);
        CallEvents.log("12433 ok callId=" + callId + " host=" + hostCall + " pending=" + groupPending + " servers=" + r.servers.size());
        if (r.servers.isEmpty()) { fail("Máy chủ không cấp đường truyền cuộc gọi nhóm"); return; }
        try { r.start(300); } catch (Exception e) { fail("Không mở được kết nối mạng"); return; }
        if (!current(myGen)) return;
        ZrtpRelay.Server sel = r.firstOk();
        String rtp = sel != null ? sel.rtp : r.servers.get(0).rtp;
        String rtcp = sel != null ? sel.rtcp : r.servers.get(0).rtcp;
        JSONArray args;
        try {
            JSONObject outer = new JSONObject().put("codec", CODEC).put("data", groupMeta(members, true).toString())
                    .put("extendData", buildExtendData(r, d, false).toString() + "\n").put("rtcpAddress", rtcp)
                    .put("rtcpAddressIPv6", "").put("rtpAddress", rtp).put("rtpAddressIPv6", "");
            args = new JSONArray().put(hostCall).put(callId).put(0).put(outer.toString()).put(sessId)
                    .put(members.toString()).put(groupId);
        } catch (Exception e) { fail("Lỗi dữ liệu nhóm"); return; }
        CallSignal.call("sendRequestCallGroup", args, (ok, body) -> { });
        // Chủ cuộc gọi nhóm bật âm thanh ngay (ctdozlite): ai nghe máy thì SFU chuyển tiếng qua.
        r.select(rtp);
        CallAudio a = new CallAudio(r::sendAudioRtp, fromUid, true);
        r.setSink(a::onRtp);
        synchronized (this) { audio = a; a.setMuted(muted); }
        app.getSystemService(AudioManager.class).setMode(AudioManager.MODE_IN_COMMUNICATION);
        a.start();
        setState(State.CALLING, "Đang gọi nhóm…");
        App.MAIN.postDelayed(() -> {
            if (current(myGen) && state != State.CONNECTED) hangup("Không ai trả lời");
        }, NO_ANSWER_MS);
    }

    private void onGroupEvent(int myGen, String act, JSONObject data) {
        long who = parseLong(data.opt("fromId") != null ? data.opt("fromId") : data.opt("uidFrom"));
        switch (act) {
            case "group_ring_ring":
                if (state == State.CALLING) setState(State.RINGING, "Đang đổ chuông…");
                break;
            case "group_answer": {
                int status = (int) parseLong(data.opt("status"));
                try {
                    JSONObject ex = new JSONObject(data.optString("extraData", "{}"));
                    if (ex.has("status")) status = ex.optInt("status");
                } catch (Exception ignored) { }
                synchronized (this) {
                    groupPending.remove(who);
                    if (status == 0 && who != 0 && who != hostCall) groupActive.add(who);
                }
                if (status == 0 && state != State.CONNECTED) {
                    synchronized (this) { connectedAt = System.currentTimeMillis(); }
                    setState(State.CONNECTED, "");
                } else {
                    notifyListener();
                }
                break;
            }
            case "group_end_call":
            case "group_cancel": {
                boolean empty;
                synchronized (this) {
                    groupActive.remove(who);
                    groupPending.remove(who);
                    empty = groupActive.isEmpty() && groupPending.isEmpty();
                }
                CallEvents.log("group member left " + who + " empty=" + empty);
                if (empty) hangup("Mọi người đã rời cuộc gọi");
                else notifyListener();
                break;
            }
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ sự kiện từ WebSocket

    void onVoipEvent(String act, JSONObject data) {
        long evCall = parseLong(data.opt("callId") != null ? data.opt("callId") : data.opt("id"));
        CallEvents.log("voip event act=" + act + " callId=" + evCall + " state=" + state);
        if ("request".equals(act)) { onIncoming(data, evCall); return; }
        final int myGen;
        synchronized (this) {
            if (!busy() || (evCall != 0 && evCall != callId)) return;
            myGen = gen;
        }
        if (group) {
            if (act.startsWith("group_")) onGroupEvent(myGen, act, data);
            return;
        }
        JSONObject params = null;
        try { params = new JSONObject(data.optString("params", "{}")); } catch (Exception ignored) { }
        if (state == State.INCOMING || (incoming && state != State.CONNECTED)) {
            // Đang đổ chuông ở máy mình: bên gọi huỷ, hoặc đã nghe/từ chối trên thiết bị khác.
            if ("cancel".equals(act) || "end_call".equals(act)) end(myGen, "Cuộc gọi nhỡ", false);
            else if ("answer".equals(act) && state == State.INCOMING) end(myGen, "Đã trả lời trên thiết bị khác", false);
            return;
        }
        switch (act) {
            case "ring_ring":
                if (state == State.CALLING || state == State.DIALING) setState(State.RINGING, "Đang đổ chuông…");
                break;
            case "answer": {
                int status = (int) parseLong(data.opt("status"));
                if (status != 0) {
                    end(myGen, status == 2 ? "Máy bận" : "Người nhận từ chối (" + status + ")", false);
                    return;
                }
                if (state == State.CONNECTED) return;
                String ser = params != null ? firstNonEmpty(params.optString("rtpSerIp"), params.optString("rtpIP")) : "";
                App.IO.execute(() -> connect(myGen, ser));
                break;
            }
            case "end_call":
            case "cancel":
                end(myGen, "Đã kết thúc", false);
                break;
            default:
                break;
        }
    }

    private void connect(int myGen, String rtpSer) {
        ZrtpRelay r;
        synchronized (this) {
            if (!current(myGen) || state == State.CONNECTED) return;
            r = relay;
            connectedAt = System.currentTimeMillis();
        }
        if (r == null) return;
        ZrtpRelay.Server s = r.select(rtpSer);
        if (s == null) { hangup("Không kết nối được máy chủ thoại"); return; }
        if (!CallAudio.supported()) CallEvents.log("opus encoder not available on this device");
        CallAudio a = new CallAudio(r::sendAudioRtp, fromUid);
        r.setSink(a::onRtp);
        synchronized (this) { audio = a; a.setMuted(muted); }
        AudioManager am = app.getSystemService(AudioManager.class);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
        a.start();
        if (isVideo) {
            CallVideo v = new CallVideo(app, new CallVideo.Out() {
                @Override public void sendVideoRtp(byte[] rtp, int len) { r.sendVideoRtp(rtp, len); }
                @Override public void sendRtcp(byte[] rtcp, int len, boolean vid) { r.sendRtcp(rtcp, len, vid); }
            }, fromUid);
            r.setVideoSink(v::onRtp);
            r.setRtcpSink(v::onRtcp);
            synchronized (this) { video = v; }
            v.start();
            App.MAIN.post(() -> KeepAliveService.setSpeaker(app, true)); // gọi video: loa ngoài như Zalo
            // ZaloCall.exe gửi IncallNegotiateInfo đều đặn trong cuộc gọi video.
            App.IO.execute(() -> {
                long lastRv = r.rxVideoBytes, lastAt = System.currentTimeMillis();
                for (int n = 1; current(myGen) && video == v; n++) {
                    r.sendVideoNegotiate();
                    v.sendFeedback(REMB_BPS); // RR + REMB: để bên kia tăng bitrate hình
                    if (n % 5 == 0) { // nhật ký: tốc độ / cỡ hình nhận mỗi 10 giây (xem chất lượng hình bên kia gửi)
                        long now = System.currentTimeMillis(), rv = r.rxVideoBytes;
                        CallEvents.log("video rx " + kbps(rv - lastRv, (now - lastAt) / 1000.0) + " kbps size="
                                + v.remoteSize() + " ping=" + r.pingMs);
                        lastRv = rv;
                        lastAt = now;
                    }
                    try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
                }
            });
        }
        setState(State.CONNECTED, "");
        // Người gọi xác nhận (408); người nhận thì bên gọi tự gửi.
        if (!incoming) CallSignal.call("sendAnswerACKCall", new JSONArray().put(partnerId).put(callId), (ok, body) -> { });
        if (isVideo) sendMediaState(); // báo camera đang bật
    }

    private long stT, stTA, stRA, stTV, stRV;
    private String stText = "";

    /**
     * Một dòng thông số cho màn hình cuộc gọi (gọi từ luồng chính ~2 lần/giây, tính lại mỗi giây): ping tới relay,
     * tốc độ tiếng (và hình nếu gọi video) gửi ↑ / nhận ↓ tính cả đầu gói RTP + relay.
     */
    synchronized String statsLine() {
        ZrtpRelay r = relay;
        if (r == null || state != State.CONNECTED) return "";
        long now = System.currentTimeMillis();
        if (now - stT < 1000 && !stText.isEmpty()) return stText;
        long ta = r.txAudioBytes, ra = r.rxAudioBytes, tv = r.txVideoBytes, rv = r.rxVideoBytes;
        if (stT > 0 && now > stT) {
            double sec = (now - stT) / 1000.0;
            StringBuilder sb = new StringBuilder();
            sb.append("Ping  ").append(r.pingMs < 0 ? "—" : r.pingMs + " ms");
            sb.append("\nÂm thanh  ↑").append(kbps(ta - stTA, sec)).append("  ↓").append(kbps(ra - stRA, sec)).append(" kbps");
            if (isVideo) {
                sb.append("\nVideo  ↑").append(kbps(tv - stTV, sec)).append("  ↓").append(kbps(rv - stRV, sec)).append(" kbps");
                CallVideo v = video;
                if (v != null && v.remoteSize() != null) sb.append("\nHình nhận  ").append(v.remoteSize());
            }
            stText = sb.toString();
        }
        stT = now; stTA = ta; stRA = ra; stTV = tv; stRV = rv;
        return stText;
    }

    private static long kbps(long bytes, double sec) {
        return Math.round(bytes * 8 / 1000.0 / sec);
    }

    synchronized boolean isVideo() { return isVideo; }

    /** Bộ hình của cuộc gọi video đang nối (null nếu gọi thoại / chưa nối). */
    synchronized CallVideo video() { return video; }

    // ------------------------------------------------------------------ gọi đến

    /*
     * Cuộc gọi đến. Máy chủ chỉ gửi lời mời (act "request" qua WebSocket 601) cho phiên kiểu Zalo PC: mỗi lần trang tải
     * xong, app gọi getLoginInfo của trang với type 24 (shim "upgradeSession"). Lời mời mang: uidN (người gọi, dùng cho
     * báo hiệu), uidFrom/uidTo (uin số của hai bên, dùng trong gói ZRTP), callId, session, params.rtpIP (relay bên gọi
     * đã chọn), params.extendData (relay, p2p), Dname, avatar. Đổ chuông: 407 ringring; nghe: 402 answer status 0 với
     * cùng relay; từ chối: 402 status 3. (ctdozlite call.rs, nhánh "request" và AnswerCall.)
     */
    private boolean incoming;
    private String inRtp = "", inRtcp = "";
    private android.media.Ringtone ringtone;
    private long upgradedAt;

    /** Trang Zalo Web vừa tải xong: nâng phiên để nhận lời mời gọi (nếu bật trong Cài đặt). */
    void onPageLoaded() {
        Context c = App.ctx;
        if (c == null || !Prefs.incomingCalls(c)) return;
        App.MAIN.postDelayed(() -> CallSignal.call("upgradeSession", new JSONArray(), (ok, body) -> {
            CallEvents.log("session upgrade ok=" + ok + " " + body);
            if (ok && body.optInt("error_code", -1) == 0) upgradedAt = System.currentTimeMillis();
        }), 3000);
    }

    private void onIncoming(JSONObject data, long evCall) {
        Context c = App.ctx;
        if (c == null || !Prefs.incomingCalls(c)) return;
        long ts = parseLong(data.opt("ts"));
        if (ts > 0 && ts < 10_000_000_000L) ts *= 1000;
        if (ts > 0 && System.currentTimeMillis() - ts > 45_000) { CallEvents.log("stale invite ignored"); return; }
        JSONObject p = new JSONObject();
        try { p = new JSONObject(data.optString("params", "{}")); } catch (Exception ignored) { }
        JSONObject ext = new JSONObject();
        try { ext = new JSONObject(p.optString("extendData", "{}").trim()); } catch (Exception ignored) { }
        boolean vid = ext.optInt("callType", 0) == 1 || data.optInt("callType", 0) == 3;
        CallEvents.log("incoming type: ext.callType=" + ext.opt("callType") + " data.callType=" + data.opt("callType")
                + " params.callType=" + p.opt("callType") + " platform=" + p.opt("platform") + " -> video=" + vid);
        final int myGen;
        final ZrtpRelay r;
        synchronized (this) {
            if (busy()) {
                CallEvents.log("incoming while busy: ignored callId=" + evCall);
                return;
            }
            app = c.getApplicationContext();
            incoming = true;
            group = false;
            isVideo = vid;
            video = null;
            audio = null;
            partnerId = firstNonEmpty(data.optString("uidN"), data.optString("uidFrom"));
            partnerName = firstNonEmpty(p.optString("Dname"), "Người dùng Zalo");
            partnerAvatar = p.optString("avatar");
            avatarBmp = null;
            callId = evCall;
            fromUid = (int) parseLong(firstNonEmpty(data.optString("uidTo"), p.optString("reveiverId"), p.optString("receiverId")));
            partnerUid = (int) parseLong(data.optString("uidFrom"));
            sessId = firstNonEmpty(p.optString("sessId"), data.optString("session"));
            inRtp = firstNonEmpty(p.optString("rtpIP"), data.optString("rtpAddress"));
            inRtcp = firstNonEmpty(p.optString("rtcpIP"), data.optString("rtcpAddress"), inRtp);
            connectedAt = 0;
            muted = false;
            r = relay = new ZrtpRelay(fromUid, partnerUid, (int) callId, false, sessId);
            myGen = ++gen;
        }
        r.addServer(inRtp, inRtcp);
        JSONArray sa = ext.optJSONArray("serverAddr");
        for (int i = 0; sa != null && i < sa.length(); i++) {
            JSONObject s = sa.optJSONObject(i);
            if (s != null) r.addServer(s.optString("rtp"), s.optString("rtcp"));
        }
        CallEvents.log("incoming call from " + partnerName + " uid=" + partnerId + " callId=" + callId + " video=" + vid
                + " relay=" + inRtp);
        setState(State.INCOMING, vid ? "Cuộc gọi video đến" : "Cuộc gọi thoại đến");
        startRinging();
        // Máy đang khoá (giữ kết nối khi khoá màn hình): giữ CPU thức suốt lúc đổ chuông, kịp xử lý nghe máy / huỷ.
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ZaloDuo:ringing");
            wl.setReferenceCounted(false);
            wl.acquire(NO_ANSWER_MS + 5_000);
        } catch (Exception ignored) { }
        final String avUrl = partnerAvatar;
        if (avUrl.startsWith("http")) App.IO.execute(() -> { // ảnh cho popup cuộc gọi đến (màn hình gọi tự tải riêng)
            android.graphics.Bitmap b = CallActivity.loadBitmap(CallActivity.hdAvatar(avUrl));
            if (b == null) b = CallActivity.loadBitmap(avUrl);
            if (b != null && current(myGen)) setAvatarBitmap(b);
        });
        App.MAIN.post(() -> {
            // Đang mở app: hiện màn hình cuộc gọi ngay; ở nền: thông báo toàn màn hình (CallNotify) lo việc này.
            if (MainActivity.visible || CallActivity.visible) {
                app.startActivity(new android.content.Intent(app, CallActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        });
        CallSignal.call("sendRingRingCall", new JSONArray().put(partnerId).put(callId), (ok, body) -> { });
        App.IO.execute(() -> {
            try { r.start(300); } catch (Exception e) { CallEvents.log("callee relay start failed " + e); }
        });
        App.MAIN.postDelayed(() -> {
            if (current(myGen) && state == State.INCOMING) end(myGen, "Cuộc gọi nhỡ", false);
        }, NO_ANSWER_MS);
    }

    /** Nhận cuộc gọi đến đang đổ chuông. */
    void answer() {
        final int myGen;
        final ZrtpRelay r;
        synchronized (this) {
            if (state != State.INCOMING) return;
            myGen = gen;
            r = relay;
        }
        stopRinging();
        setState(State.DIALING, "Đang kết nối…");
        App.MAIN.post(() -> KeepAliveService.setMediaActive(app, true, isVideo));
        App.IO.execute(() -> {
            // Chờ bắt tay relay xong (bắt đầu từ lúc đổ chuông, thường đã xong).
            for (int i = 0; i < 40 && r.firstOk() == null; i++) {
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
            if (!current(myGen)) return;
            ZrtpRelay.Server named = r.find(inRtp);
            ZrtpRelay.Server use = named != null && named.token != 0 ? named : r.firstOk();
            if (use == null) { fail("Không kết nối được máy chủ thoại"); return; }
            String ext = buildCalleeExtend(r, use, isVideo).toString() + "\n";
            JSONArray args = new JSONArray().put(partnerId).put(callId).put(0).put(CODEC).put(ext)
                    .put(use.rtcp).put(use.rtp).put(sessId);
            CallSignal.call("sendAnswerCall", args, (ok, body) -> {
                if (!current(myGen)) return;
                if (CallSignal.data(ok, body) == null) { fail("Không trả lời được (" + CallSignal.describe(body) + ")"); return; }
                connect(myGen, use.rtp);
            });
        });
    }

    /** Từ chối cuộc gọi đến. */
    void decline() {
        final int myGen;
        synchronized (this) {
            if (state != State.INCOMING) return;
            myGen = gen;
        }
        CallSignal.call("sendAnswerCall", new JSONArray().put(partnerId).put(callId).put(3).put(CODEC).put("")
                .put(inRtcp).put(inRtp).put(sessId), (ok, body) -> { });
        end(myGen, "Đã từ chối", true);
    }

    /** extendData của 402 (ctdozlite build_callee_extend_data): relay đã chọn, p2p, select2side. */
    private static JSONObject buildCalleeExtend(ZrtpRelay r, ZrtpRelay.Server use, boolean video) {
        JSONObject ext = new JSONObject();
        try {
            JSONArray p2p = new JSONArray();
            String localIp = ZrtpRelay.localIp();
            p2p.put(new JSONObject().put("ip", localIp).put("port", r.localPort()).put("type", 0));
            String pub = r.publicAddr();
            if (pub != null) {
                int i = Math.max(pub.lastIndexOf('|'), pub.lastIndexOf(':'));
                if (i > 0) {
                    String ip = pub.substring(0, i).trim();
                    int port = (int) parseLong(pub.substring(i + 1).trim());
                    if (!ip.equals(localIp) && port > 0) p2p.put(new JSONObject().put("ip", ip).put("port", port).put("type", 1));
                }
            }
            ext.put("callType", video ? 1 : 0).put("fecTP", 0).put("gccAudio", 1).put("gccEarlyCall", 0).put("gccMode", 1)
                    .put("gccSVLR", 1).put("maxFT", 60).put("newZrtc", 1).put("numServers", 0).put("p2p", p2p)
                    .put("packetMode", 2).put("platform", 2).put("sP2P", 0).put("select2side", 1)
                    .put("serverAddr", new JSONArray().put(new JSONObject().put("rtcp", use.rtcp).put("rtcpIPv6", "")
                            .put("rtp", use.rtp).put("rtpIPv6", "").put("tpType", 0)))
                    .put("spTcp", 1).put("srtcp", 0).put("srtpMode", 0).put("supportCallBusy", 1).put("supportHevcDecode", 0)
                    .put("tpType", 0)
                    .put("video", new JSONObject().put("codec", new JSONArray().put(new JSONObject().put("name", "h264").put("payload", 97))));
        } catch (Exception e) {
            CallEvents.log("callee extendData error " + e);
        }
        return ext;
    }

    private void startRinging() {
        App.MAIN.post(() -> {
            try {
                stopRinging();
                // Cài đặt → Cuộc gọi → Nhạc chuông (null = im lặng: chỉ rung theo kênh thông báo cuộc gọi đến).
                android.net.Uri uri = Prefs.ringtone(app);
                if (uri != null && android.provider.Settings.System.DEFAULT_RINGTONE_URI.equals(uri)) {
                    uri = android.media.RingtoneManager.getActualDefaultRingtoneUri(app, android.media.RingtoneManager.TYPE_RINGTONE);
                }
                if (uri == null) return;
                ringtone = android.media.RingtoneManager.getRingtone(app, uri);
                if (ringtone == null) return;
                ringtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                if (android.os.Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
                ringtone.play();
            } catch (Exception e) {
                CallEvents.log("ringtone error " + e);
            }
        });
    }

    private void stopRinging() {
        App.MAIN.post(() -> {
            if (ringtone != null) { try { ringtone.stop(); } catch (Exception ignored) { } ringtone = null; }
        });
    }

    // ------------------------------------------------------------------ điều khiển

    void hangup(String why) {
        if (state == State.INCOMING) { decline(); return; }
        final int myGen;
        final boolean connected;
        synchronized (this) {
            if (!busy()) return;
            myGen = gen;
            connected = state == State.CONNECTED;
        }
        if (group) {
            // 12438 endcall (đã có người nghe) / 12437 cancel: calleeId = hostCall, callType 0, partners = thành viên
            JSONArray args = new JSONArray().put(hostCall).put(callId).put(0).put(hostCall).put("").put(groupMembers);
            CallSignal.call(connected ? "sendEndCallGroup" : "sendCancelCallGroup", args, (ok, body) -> { });
        } else if (connected || incoming) {
            CallSignal.call("sendEndCall", new JSONArray().put(partnerId).put(callId), (ok, body) -> { });
        } else if (state != State.DIALING || callId != 0) {
            CallSignal.call("sendCancelCall", new JSONArray().put(partnerId).put(callId).put(1), (ok, body) -> { });
        }
        end(myGen, why == null ? "Đã kết thúc" : why, true);
    }

    void setMuted(boolean m) {
        CallAudio a;
        synchronized (this) { muted = m; a = audio; }
        if (a != null) a.setMuted(m);
        sendMediaState();
        notifyListener();
    }

    /** Camera bật/tắt (gọi video): dừng/mở camera và báo bên kia. */
    void setCameraOn(boolean on) {
        CallVideo v = video();
        if (v == null) return;
        v.setCameraOn(on);
        sendMediaState();
        notifyListener();
    }

    /**
     * /api/voicecall/conf (sendOnOffCamMic): báo bên kia trạng thái camera / micro để họ hiện biểu tượng tắt tiếng /
     * tắt camera. Thử trên Zalo PC: 1 = đang tắt, 0 = đang bật (gửi isOnMic 1 thì PC hiện micro gạch đỏ).
     */
    private void sendMediaState() {
        if (state != State.CONNECTED) return;
        CallVideo v = video();
        int camOff = v == null || !v.cameraOn() ? 1 : 0;
        int micOff = muted() ? 1 : 0;
        CallSignal.call("sendOnOffCamMic", new JSONArray().put(partnerId).put(callId).put(camOff).put(micOff).put(""),
                (ok, body) -> { });
    }

    void setSpeaker(boolean on) {
        if (app != null) KeepAliveService.setSpeaker(app, on);
        notifyListener();
    }

    // ------------------------------------------------------------------ nội bộ

    private void fail(String why) {
        int myGen;
        synchronized (this) { myGen = gen; }
        CallEvents.log("call failed: " + why);
        // Đã tạo cuộc gọi trên máy chủ thì huỷ để máy bên kia không đổ chuông mãi (gọi đến: báo từ chối).
        if (incoming) {
            CallSignal.call("sendAnswerCall", new JSONArray().put(partnerId).put(callId).put(3).put(CODEC).put("")
                    .put(inRtcp).put(inRtp).put(sessId), (ok, body) -> { });
        } else if (relay != null) {
            CallSignal.call("sendCancelCall", new JSONArray().put(partnerId).put(callId).put(1), (ok, body) -> { });
        }
        end(myGen, why, false);
    }

    private void end(int myGen, String why, boolean byMe) {
        ZrtpRelay r;
        CallAudio a;
        CallVideo v;
        long dur;
        synchronized (this) {
            if (myGen != gen || !busy()) return;
            r = relay; a = audio; v = video;
            relay = null; audio = null; video = null;
            stT = 0; stText = "";
            dur = connectedAt > 0 ? (System.currentTimeMillis() - connectedAt) / 1000 : 0;
        }
        CallEvents.log("call end (" + (byMe ? "me" : "peer/server") + "): " + why + " duration=" + dur + "s");
        stopRinging();
        boolean wasConnected;
        synchronized (this) { wasConnected = connectedAt > 0; }
        // Gọi đi không ai nghe / huỷ cũng ghi (Zalo PC hiện "Cuộc gọi thoại đi · 0 phút 0 giây"); gọi đến chưa nghe thì máy
        // chủ tự ghi "Bạn bị nhỡ".
        boolean placed;
        synchronized (this) { placed = !incoming && sessId != null && !sessId.isEmpty(); } // 401 đã tạo cuộc gọi trên máy chủ
        if (!group && (wasConnected || placed) && callId != 0) {
            // 406 logendcall (ZaloCall.exe gửi sau 409, ctdozlite): ghi cuộc gọi vào lịch sử — tin "Cuộc gọi thoại đi/đến"
            // trong khung chat của chính mình. role 1 = người gọi, 0 = người nhận.
            JSONArray la = new JSONArray().put(callId).put(partnerId).put(0).put("{}").put(dur).put(incoming ? 0 : 1);
            CallSignal.call("sendLogEndCall", la, (ok, body) -> { });
        }
        if (a != null) a.stop();
        if (v != null) v.stop();
        if (r != null) App.IO.execute(r::stop);
        setState(State.ENDED, why);
        App.MAIN.post(() -> {
            if (app == null) return;
            AudioManager am = app.getSystemService(AudioManager.class);
            am.setMode(AudioManager.MODE_NORMAL);
            KeepAliveService.setMediaActive(app, false, false);
        });
    }

    private boolean current(int myGen) {
        synchronized (this) { return myGen == gen && busy(); }
    }

    private void setState(State s, String d) {
        synchronized (this) { state = s; detail = d == null ? "" : d; }
        CallEvents.log("state " + s + (d == null || d.isEmpty() ? "" : " (" + d + ")"));
        notifyListener();
    }

    private void notifyListener() {
        App.MAIN.post(() -> {
            KeepAliveService.refreshNotification();
            Listener l = listener;
            if (l != null) { State s; String d; synchronized (this) { s = state; d = detail; } l.onCallState(s, d); }
        });
    }

    private static void collectServers(ZrtpRelay r, JSONObject d) {
        JSONArray arr = d.optJSONArray("servers");
        if (arr == null && d.optJSONObject("callSetting") != null) arr = d.optJSONObject("callSetting").optJSONArray("servers");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject s = arr.optJSONObject(i);
                if (s == null) continue;
                String rtp = firstNonEmpty(s.optString("rtpaddr"), s.optString("rtp"));
                r.addServer(rtp, firstNonEmpty(s.optString("rtcpaddr"), s.optString("rtcp"), rtp));
            }
        }
        String top = firstNonEmpty(d.optString("rtpIP"), d.optString("rtpAddress"), d.optString("rtpSerIp"));
        if (!top.isEmpty()) r.addServer(top, firstNonEmpty(d.optString("rtcpIP"), d.optString("rtcpAddress"), top));
    }

    /** extendData của 416 (theo ZaloCall.exe qua ctdozlite): relay, kết quả đo, địa chỉ p2p, khả năng của máy. */
    private static JSONObject buildExtendData(ZrtpRelay r, JSONObject d, boolean video) {
        JSONObject ext = new JSONObject();
        try {
            JSONArray srcServers = d.optJSONArray("servers");
            JSONArray serverAddr = new JSONArray(), serverResult = new JSONArray();
            if (srcServers != null) {
                for (int i = 0; i < srcServers.length(); i++) {
                    JSONObject s = srcServers.optJSONObject(i);
                    if (s == null) continue;
                    String rtp = s.optString("rtpaddr");
                    ZrtpRelay.Server rs = r.find(rtp);
                    serverAddr.put(new JSONObject().put("rtcp", s.optString("rtcpaddr")).put("rtcpIPv6", s.optString("rtcpIPv6"))
                            .put("rtp", rtp).put("rtpIPv6", s.optString("rtpIPv6")).put("tpType", s.optInt("tpType", 0)));
                    serverResult.put(new JSONObject()
                            .put("recv", rs != null && rs.echoRecv > 0 ? rs.echoRecv : 14)
                            .put("rtcp", s.optString("rtcpaddr")).put("rtcpIPv6", s.optString("rtcpIPv6"))
                            .put("rtp", rtp).put("rtpIPv6", s.optString("rtpIPv6"))
                            .put("rtt", rs != null && rs.rttMs > 0 ? rs.rttMs : 25)
                            .put("spTcp", 1).put("tpType", s.optInt("tpType", 0)));
                }
            } else {
                ZrtpRelay.Server f = r.servers.get(0);
                serverAddr.put(new JSONObject().put("rtcp", f.rtcp).put("rtcpIPv6", "").put("rtp", f.rtp).put("rtpIPv6", "").put("tpType", 0));
            }
            JSONArray p2p = new JSONArray();
            String localIp = ZrtpRelay.localIp();
            p2p.put(new JSONObject().put("ip", localIp).put("port", r.localPort()).put("type", 0));
            String pub = r.publicAddr();
            if (pub != null) {
                int i = Math.max(pub.lastIndexOf('|'), pub.lastIndexOf(':'));
                if (i > 0) {
                    String ip = pub.substring(0, i).trim();
                    int port = (int) parseLong(pub.substring(i + 1).trim());
                    if (!ip.equals(localIp) && port > 0) p2p.put(new JSONObject().put("ip", ip).put("port", port).put("type", 1));
                }
            }
            ext.put("callType", video ? 1 : 0).put("fecTP", 0).put("gccAudio", 1).put("gccEarlyCall", 0).put("gccMode", 1)
                    .put("gccSVLR", 1).put("maxFT", 60).put("newZrtc", 1).put("numServers", serverResult.length())
                    .put("p2p", p2p).put("packetMode", 2).put("platform", 2).put("sP2P", 0)
                    .put("serverAddr", serverAddr).put("serverResult", serverResult).put("spTcp", 1).put("srtcp", 0)
                    .put("srtpMode", 0).put("supportCallBusy", 1).put("supportHevcDecode", 0).put("tpType", 0)
                    .put("video", new JSONObject().put("codec", new JSONArray().put(new JSONObject().put("name", "h264").put("payload", 97))));
        } catch (Exception e) {
            CallEvents.log("extendData error " + e);
        }
        return ext;
    }

    static long parseLong(Object o) {
        if (o == null) return 0;
        if (o instanceof Number) return ((Number) o).longValue();
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return 0; }
    }

    private static String firstNonEmpty(String... v) {
        for (String s : v) if (s != null && !s.isEmpty() && !"null".equals(s)) return s;
        return "";
    }

    private static String msgOr(JSONObject d, String def) {
        String m = d.optString("msg");
        return m.isEmpty() ? def : m;
    }
}
